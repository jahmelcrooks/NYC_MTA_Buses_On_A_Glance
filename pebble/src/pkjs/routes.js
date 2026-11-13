// Pure functions (no Pebble or network APIs) so they can be unit-tested with plain Node.
// This mirrors the Wear OS app's BusMapping.kt: nearest stop wins, starred buses first.

var UNKNOWN_DESTINATION = 'Unknown';
var MAX_TIMES = 3;
var DEPARTED_GRACE_SECONDS = 60;
var METERS_PER_DEGREE_LAT = 111320;

function distanceMeters(lat1, lon1, lat2, lon2) {
  var toRad = Math.PI / 180;
  var dLat = (lat2 - lat1) * toRad;
  var dLon = (lon2 - lon1) * toRad;
  var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 2 * 6371000 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
}

/** Total lat/lon span (degrees) of a box reaching radiusMeters either side of the centre. */
function searchSpans(radiusMeters, latitude) {
  return {
    latSpan: 2 * radiusMeters / METERS_PER_DEGREE_LAT,
    lonSpan: 2 * radiusMeters / (METERS_PER_DEGREE_LAT * Math.cos(latitude * Math.PI / 180))
  };
}

/** MTA sometimes sends a value as a string, sometimes as a one-element array. */
function text(value) {
  if (value === null || value === undefined) return '';
  if (Array.isArray(value)) return value.length ? text(value[0]) : '';
  return String(value);
}

/** Thrown for problems the user can act on; `kind` is 'key', 'network' or 'server'. */
function BusError(kind, message) {
  this.kind = kind;
  this.message = message;
}

/** Stops from a stops-for-location.json response. */
function parseStops(json) {
  if (!json || typeof json !== 'object') throw new BusError('server', 'MTA data unavailable');
  if (json.code === 401 || json.code === 403) throw new BusError('key', 'MTA rejected the API key');
  if (json.code && (json.code < 200 || json.code > 299)) throw new BusError('server', 'MTA error ' + json.code);
  var data = json.data || {};
  var raw = (data.stops && data.stops.length ? data.stops : data.list) || [];
  var stops = [];
  for (var i = 0; i < raw.length; i++) {
    var s = raw[i] || {};
    var lat = Number(s.lat);
    var lon = Number(s.lon);
    if (!s.id || isNaN(lat) || isNaN(lon)) continue;
    stops.push({ id: String(s.id), name: text(s.name), lat: lat, lon: lon });
  }
  return stops;
}

/** Bus journeys from a SIRI stop-monitoring.json response. */
function parseJourneys(json) {
  var deliveries = (((json || {}).Siri || {}).ServiceDelivery || {}).StopMonitoringDelivery || [];
  var journeys = [];
  for (var d = 0; d < deliveries.length; d++) {
    // Some errors (e.g. a bad key) come back as HTTP 200 with an ErrorCondition inside.
    var error = (deliveries[d] || {}).ErrorCondition;
    if (error) {
      var description = text(error.Description || (error.OtherError || {}).ErrorText);
      if (/key/i.test(description)) throw new BusError('key', 'MTA rejected the API key');
    }
    var visits = (deliveries[d] || {}).MonitoredStopVisit || [];
    for (var v = 0; v < visits.length; v++) {
      var j = (visits[v] || {}).MonitoredVehicleJourney;
      if (!j) continue;
      var call = j.MonitoredCall || {};
      var expected = Date.parse(text(call.ExpectedArrivalTime));
      var distances = (call.Extensions || {}).Distances || {};
      journeys.push({
        route: text(j.PublishedLineName),
        destination: text(j.DestinationName) || UNKNOWN_DESTINATION,
        vehicle: j.VehicleRef ? text(j.VehicleRef) : null,
        expected: isNaN(expected) ? null : Math.floor(expected / 1000),
        away: text(distances.PresentableDistance)
      });
    }
  }
  return journeys;
}

function favoriteKey(stopId, route, destination) {
  return stopId + '|' + route + '|' + destination;
}

/**
 * Turns per-stop journeys into list items, one per route + destination.
 * stopArrivals: [{ stop, meters, journeys }]. favorites: object used as a set of favoriteKey()s.
 */
function buildItems(stopArrivals, favorites, nowSeconds, maxItems) {
  var sorted = stopArrivals.slice().sort(function (a, b) { return a.meters - b.meters; });
  var byKey = {};
  var order = [];

  for (var s = 0; s < sorted.length; s++) {
    var entry = sorted[s];
    var groups = {};
    var groupOrder = [];
    for (var j = 0; j < entry.journeys.length; j++) {
      var journey = entry.journeys[j];
      if (!journey.route) continue;
      var key = journey.route + '|' + journey.destination;
      if (!groups[key]) { groups[key] = []; groupOrder.push(key); }
      groups[key].push(journey);
    }

    for (var g = 0; g < groupOrder.length; g++) {
      var k = groupOrder[g];
      var first = groups[k][0];
      var existing = byKey[k];
      var thisStarred = !!favorites[favoriteKey(entry.stop.id, first.route, first.destination)];
      if (existing) {
        // A nearer stop already covers it, unless the user starred this stop and not that one.
        if (existing.starred || !thisStarred) continue;
      } else {
        order.push(k);
      }
      byKey[k] = makeItem(entry, groups[k], thisStarred, nowSeconds);
    }
  }

  var items = order.map(function (k) { return byKey[k]; })
    .filter(function (item) { return item.times.length > 0 || item.starred; });
  items.sort(function (a, b) {
    if (a.starred !== b.starred) return a.starred ? -1 : 1;
    if (a.meters !== b.meters) return a.meters - b.meters;
    return (a.times[0] || Infinity) - (b.times[0] || Infinity);
  });
  return items.slice(0, maxItems);
}

function makeItem(entry, journeys, starred, nowSeconds) {
  var seen = {};
  var upcoming = [];
  for (var i = 0; i < journeys.length; i++) {
    var j = journeys[i];
    if (j.expected === null || j.expected < nowSeconds - DEPARTED_GRACE_SECONDS) continue;
    var id = j.vehicle || ('t' + j.expected);
    if (seen[id]) continue; // the same bus listed twice
    seen[id] = true;
    upcoming.push(j);
  }
  upcoming.sort(function (a, b) { return a.expected - b.expected; });
  var next = upcoming[0];
  var away = next && next.away && !/approaching/i.test(next.away) ? next.away : '';
  return {
    route: journeys[0].route,
    destination: journeys[0].destination,
    stopId: entry.stop.id,
    stopName: entry.stop.name,
    meters: entry.meters,
    times: upcoming.slice(0, MAX_TIMES).map(function (x) { return x.expected; }),
    away: away,
    starred: starred
  };
}

function formatDistance(meters, metric) {
  if (metric) {
    return meters < 100 ? Math.round(meters) + ' m' : (meters / 1000).toFixed(1) + ' km';
  }
  var miles = meters * 0.000621371;
  return miles < 0.1 ? Math.round(meters * 3.28084) + ' ft' : miles.toFixed(1) + ' mi';
}

/** Keeps a string within a C buffer of `bytes` (incl. the terminator), never splitting a UTF-8 char. */
function fit(value, bytes) {
  var s = String(value || '');
  while (utf8Length(s) > bytes - 1) s = s.slice(0, -1);
  return s;
}

function utf8Length(s) {
  var n = 0;
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xD800 && c <= 0xDBFF) { n += 4; i++; }
    else n += 3;
  }
  return n;
}

var RADIUS_OPTIONS_MILES = [0.25, 0.5, 0.75, 1];

function normalizeRadius(raw) {
  var value = Number(raw);
  if (isNaN(value)) return 0.5;
  var best = RADIUS_OPTIONS_MILES[0];
  for (var i = 1; i < RADIUS_OPTIONS_MILES.length; i++) {
    if (Math.abs(RADIUS_OPTIONS_MILES[i] - value) < Math.abs(best - value)) best = RADIUS_OPTIONS_MILES[i];
  }
  return best;
}

function cleanApiKey(raw) {
  return String(raw || '').replace(/\s+/g, '').replace(/^["']+|["']+$/g, '');
}

module.exports = {
  BusError: BusError,
  distanceMeters: distanceMeters,
  searchSpans: searchSpans,
  parseStops: parseStops,
  parseJourneys: parseJourneys,
  favoriteKey: favoriteKey,
  buildItems: buildItems,
  formatDistance: formatDistance,
  fit: fit,
  normalizeRadius: normalizeRadius,
  cleanApiKey: cleanApiKey,
  RADIUS_OPTIONS_MILES: RADIUS_OPTIONS_MILES
};
