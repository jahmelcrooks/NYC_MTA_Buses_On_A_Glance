// Runs inside the Pebble app on the phone: it has the GPS and the internet the watch lacks.
// Flow: watch asks -> locate -> nearby stops -> live arrivals -> send a short list to the watch.

var routes = require('./routes');
var config = require('./config');

var BASE_URL = 'https://bustime.mta.info/';
var MAX_STOPS_TO_QUERY = 6;
var MAX_ITEMS = 12; // must match MAX_ITEMS in main.c
var REQUEST_TIMEOUT_MS = 12000;

// C buffer sizes (bytes, including the terminator); keep in sync with main.c.
var SIZES = { ROUTE: 10, DEST: 32, STOP: 32, DIST: 12, AWAY: 24, STATUS: 64 };

var lastStopArrivals = null; // reused when the user stars something, so no new download
var refreshing = false;
var refreshStartedAt = 0;
var pendingRefresh = false; // a refresh asked for while one was running (e.g. new settings)
var STUCK_REFRESH_MS = 60000;

// Each list sent to the watch gets a generation number. The watch sends it back with a star
// request, so a star always applies to the list the user was actually looking at.
var GEN_WRAP = 10000;
var generation = 0;
var itemsByGen = {};

// ---- settings and favorites (localStorage survives app restarts and updates) ----

function loadSettings() {
  var s = {};
  try { s = JSON.parse(localStorage.getItem('settings') || '{}') || {}; } catch (e) { s = {}; }
  return {
    apiKey: routes.cleanApiKey(s.apiKey),
    radiusMiles: routes.normalizeRadius(s.radiusMiles === undefined ? 0.5 : s.radiusMiles),
    metric: !!s.metric
  };
}

function saveSettings(s) {
  localStorage.setItem('settings', JSON.stringify(s));
}

function loadFavorites() {
  try { return JSON.parse(localStorage.getItem('favorites') || '{}') || {}; } catch (e) { return {}; }
}

function saveFavorites(f) {
  localStorage.setItem('favorites', JSON.stringify(f));
}

// ---- sending to the watch: one message at a time, each waiting for the previous ack ----

var queue = [];
var sending = false;

function enqueue(message) {
  queue.push(message);
  pump();
}

function pump() {
  if (sending || queue.length === 0) return;
  sending = true;
  var message = queue.shift();
  var attempts = 0;
  var attempt = function () {
    var settled = false;
    var fail = function () {
      if (settled) return;
      settled = true;
      clearTimeout(watchdog);
      attempts++;
      if (attempts < 3) {
        setTimeout(attempt, 300);
      } else {
        // Give up; the watch only shows complete lists and asks again within a minute.
        queue = [];
        sending = false;
      }
    };
    // If the watch disconnects mid-send, neither callback may ever fire.
    var watchdog = setTimeout(fail, 10000);
    Pebble.sendAppMessage(message, function () {
      if (settled) return;
      settled = true;
      clearTimeout(watchdog);
      sending = false;
      pump();
    }, fail);
  };
  attempt();
}

function sendStatus(message) {
  enqueue({ STATUS: routes.fit(message, SIZES.STATUS) });
}

function sendItems(items, metric) {
  generation = (generation + 1) % GEN_WRAP;
  itemsByGen[generation] = items;
  delete itemsByGen[(generation + GEN_WRAP - 3) % GEN_WRAP]; // keep the last few lists
  if (items.length === 0) {
    // An empty list replaces whatever the watch shows, with the reason as the message.
    enqueue({ COUNT: 0, GEN: generation, STATUS: routes.fit('No upcoming buses nearby', SIZES.STATUS) });
    return;
  }
  enqueue({ COUNT: items.length, GEN: generation });
  items.forEach(function (item, index) {
    enqueue({
      INDEX: index,
      ROUTE: routes.fit(item.route, SIZES.ROUTE),
      DEST: routes.fit(item.destination, SIZES.DEST),
      STOP: routes.fit(item.stopName, SIZES.STOP),
      DIST: routes.fit(routes.formatDistance(item.meters, metric), SIZES.DIST),
      AWAY: routes.fit(item.away, SIZES.AWAY),
      T1: item.times[0] || 0,
      T2: item.times[1] || 0,
      T3: item.times[2] || 0,
      STARRED: item.starred ? 1 : 0
    });
  });
}

// ---- network ----

function getJson(path, params) {
  return new Promise(function (resolve, reject) {
    var query = Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
    }).join('&');
    var xhr = new XMLHttpRequest();
    var done = false;
    var timer = setTimeout(function () {
      if (done) return;
      done = true;
      try { xhr.abort(); } catch (e) { /* ignore */ }
      reject(new routes.BusError('network', 'No connection'));
    }, REQUEST_TIMEOUT_MS);
    xhr.onload = function () {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (xhr.status === 401 || xhr.status === 403) {
        reject(new routes.BusError('key', 'MTA rejected the API key'));
        return;
      }
      if (xhr.status < 200 || xhr.status > 299) {
        reject(new routes.BusError('server', 'MTA data unavailable'));
        return;
      }
      try {
        resolve(JSON.parse(xhr.responseText));
      } catch (e) {
        reject(new routes.BusError('server', 'Unreadable MTA data'));
      }
    };
    xhr.onerror = function () {
      if (done) return;
      done = true;
      clearTimeout(timer);
      reject(new routes.BusError('network', 'No connection'));
    };
    xhr.open('GET', BASE_URL + path + '?' + query, true);
    xhr.send();
  });
}

function locate() {
  return new Promise(function (resolve, reject) {
    var done = false;
    var fail = function () {
      if (done) return;
      done = true;
      reject(new routes.BusError('location', 'Can\'t get location. Is phone GPS on?'));
    };
    // Some phones never call back (e.g. while a permission prompt is open).
    var timer = setTimeout(fail, 20000);
    navigator.geolocation.getCurrentPosition(
      function (pos) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        resolve(pos.coords);
      },
      function () { clearTimeout(timer); fail(); },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 }
    );
  });
}

function refresh() {
  if (refreshing && Date.now() - refreshStartedAt < STUCK_REFRESH_MS) {
    pendingRefresh = true;
    return;
  }
  var settings = loadSettings();
  if (!settings.apiKey) {
    sendStatus('Add your MTA API key in the Pebble app settings');
    return;
  }
  refreshing = true;
  refreshStartedAt = Date.now();
  pendingRefresh = false;
  var radiusMeters = settings.radiusMiles * 1609.344;

  locate()
    .then(function (coords) {
      var spans = routes.searchSpans(radiusMeters, coords.latitude);
      return getJson('api/where/stops-for-location.json', {
        lat: coords.latitude,
        lon: coords.longitude,
        latSpan: spans.latSpan,
        lonSpan: spans.lonSpan,
        radius: Math.round(radiusMeters),
        key: settings.apiKey
      }).then(function (json) {
        return routes.parseStops(json)
          .map(function (stop) {
            return { stop: stop, meters: routes.distanceMeters(coords.latitude, coords.longitude, stop.lat, stop.lon) };
          })
          .filter(function (x) { return x.meters <= radiusMeters; })
          .sort(function (a, b) { return a.meters - b.meters; })
          .slice(0, MAX_STOPS_TO_QUERY);
      });
    })
    .then(function (nearby) {
      if (nearby.length === 0) {
        throw new routes.BusError('empty', 'No bus stops within ' + settings.radiusMiles + ' mi');
      }
      return Promise.all(nearby.map(function (x) {
        return getJson('api/siri/stop-monitoring.json', {
          MonitoringRef: x.stop.id,
          version: '2',
          key: settings.apiKey
        }).then(function (json) {
          return { stop: x.stop, meters: x.meters, journeys: routes.parseJourneys(json) };
        }, function (error) {
          return { error: error };
        });
      }));
    })
    .then(function (results) {
      var ok = results.filter(function (r) { return !r.error; });
      if (ok.length === 0) throw results[0].error;
      lastStopArrivals = ok;
      sendItems(build(ok), settings.metric);
    })
    .catch(function (error) {
      sendStatus(error && error.message ? error.message : 'Something went wrong');
    })
    .then(function () {
      refreshing = false;
      if (pendingRefresh) refresh();
    });
}

function build(stopArrivals) {
  return routes.buildItems(stopArrivals, loadFavorites(), Math.floor(Date.now() / 1000), MAX_ITEMS);
}

/** value = generation * MAX_ITEMS + row, as sent by the watch. */
function toggleStar(value) {
  var items = itemsByGen[Math.floor(value / MAX_ITEMS)];
  var item = items && items[value % MAX_ITEMS];
  if (!item || !lastStopArrivals) return; // a list we no longer know: ignore
  var favorites = loadFavorites();
  var key = routes.favoriteKey(item.stopId, item.route, item.destination);
  if (favorites[key]) delete favorites[key]; else favorites[key] = true;
  saveFavorites(favorites);
  sendItems(build(lastStopArrivals), loadSettings().metric);
}

// ---- events ----

Pebble.addEventListener('ready', function () {
  refresh();
});

Pebble.addEventListener('appmessage', function (e) {
  var payload = e.payload || {};
  if (payload.TOGGLE_STAR !== undefined) {
    toggleStar(payload.TOGGLE_STAR);
  } else if (payload.REQUEST !== undefined) {
    refresh();
  }
});

Pebble.addEventListener('showConfiguration', function () {
  Pebble.openURL(config.configPageUrl(loadSettings(), routes.RADIUS_OPTIONS_MILES));
});

Pebble.addEventListener('webviewclosed', function (e) {
  var value = config.parseConfigResponse(e && e.response);
  if (!value) return;
  saveSettings({
    apiKey: routes.cleanApiKey(value.apiKey),
    radiusMiles: routes.normalizeRadius(value.radiusMiles),
    metric: !!value.metric
  });
  refresh();
});
