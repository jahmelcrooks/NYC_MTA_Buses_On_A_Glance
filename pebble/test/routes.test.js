// Run with: npm test  (plain Node, no dependencies)
var test = require('node:test');
var assert = require('node:assert');
var r = require('../src/pkjs/routes');
var config = require('../src/pkjs/config');

var NOW = Date.parse('2026-11-11T19:00:00Z') / 1000;

function journey(route, dest, vehicle, secondsFromNow, away) {
  return { route: route, destination: dest, vehicle: vehicle,
    expected: secondsFromNow === null ? null : NOW + secondsFromNow, away: away || '' };
}
function stop(id, name) { return { id: id, name: name, lat: 40.65, lon: -73.95 }; }

test('nearest stop wins and the same bus is listed once', function () {
  var items = r.buildItems([
    { stop: stop('far', 'Far St'), meters: 300, journeys: [journey('B41', 'DOWNTOWN', 'V2', 600)] },
    { stop: stop('near', 'Near St'), meters: 100, journeys: [
      journey('B41', 'DOWNTOWN', 'V1', 120), journey('B41', 'DOWNTOWN', 'V1', 120)] }
  ], {}, NOW, 12);
  assert.strictEqual(items.length, 1);
  assert.strictEqual(items[0].stopName, 'Near St');
  assert.deepStrictEqual(items[0].times, [NOW + 120]);
});

test('a starred farther stop stays visible and sorts first', function () {
  var favorites = {};
  favorites[r.favoriteKey('far', 'B41', 'DOWNTOWN')] = true;
  var items = r.buildItems([
    { stop: stop('near', 'Near St'), meters: 100, journeys: [
      journey('B41', 'DOWNTOWN', 'V1', 120), journey('B44', 'SHEEPSHEAD', 'V3', 60)] },
    { stop: stop('far', 'Far St'), meters: 300, journeys: [journey('B41', 'DOWNTOWN', 'V2', 600)] }
  ], favorites, NOW, 12);
  assert.strictEqual(items[0].route, 'B41');
  assert.strictEqual(items[0].stopName, 'Far St');
  assert.strictEqual(items[0].starred, true);
  assert.strictEqual(items[1].route, 'B44');
});

test('departed and unpredicted buses are dropped, times sorted and capped at 3', function () {
  var items = r.buildItems([{ stop: stop('s', 'S'), meters: 50, journeys: [
    journey('M15', 'SOUTH FERRY', 'a', -300), journey('M15', 'SOUTH FERRY', 'b', 900),
    journey('M15', 'SOUTH FERRY', 'c', 60, '2 stops away'), journey('M15', 'SOUTH FERRY', 'd', null),
    journey('M15', 'SOUTH FERRY', 'e', 400), journey('M15', 'SOUTH FERRY', 'f', 1200)] }], {}, NOW, 12);
  assert.deepStrictEqual(items[0].times, [NOW + 60, NOW + 400, NOW + 900]);
  assert.strictEqual(items[0].away, '2 stops away');
});

test('approaching text is hidden and routes with no times are dropped unless starred', function () {
  var items = r.buildItems([{ stop: stop('s', 'S'), meters: 50, journeys: [
    journey('B8', 'X', 'a', 30, 'approaching'), journey('B9', 'Y', 'b', null)] }], {}, NOW, 12);
  assert.strictEqual(items.length, 1);
  assert.strictEqual(items[0].away, '');
});

test('parseJourneys handles arrays, missing fields and bad times', function () {
  var json = { Siri: { ServiceDelivery: { StopMonitoringDelivery: [{ MonitoredStopVisit: [
    { MonitoredVehicleJourney: { PublishedLineName: ['B41'], DestinationName: 'DOWNTOWN', VehicleRef: 'MTA_1',
      MonitoredCall: { ExpectedArrivalTime: '2026-11-11T14:05:00.000-05:00',
        Extensions: { Distances: { PresentableDistance: '1 stop away' } } } } },
    { MonitoredVehicleJourney: { PublishedLineName: 'B44', MonitoredCall: { ExpectedArrivalTime: 'nope' } } },
    {}
  ] }] } } };
  var j = r.parseJourneys(json);
  assert.strictEqual(j.length, 2);
  assert.strictEqual(j[0].route, 'B41');
  assert.strictEqual(j[0].expected, NOW + 300);
  assert.strictEqual(j[0].away, '1 stop away');
  assert.strictEqual(j[1].destination, 'Unknown');
  assert.strictEqual(j[1].expected, null);
  assert.deepStrictEqual(r.parseJourneys(null), []);
});

test('parseStops reads stops or list, skips bad rows, reports key errors', function () {
  var stops = r.parseStops({ code: 200, data: { list: [
    { id: 'MTA_1', name: 'Church Av', lat: 40.6, lon: -73.9 }, { id: '', lat: 1, lon: 1 }, { id: 'x' }] } });
  assert.deepStrictEqual(stops.map(function (s) { return s.id; }), ['MTA_1']);
  assert.throws(function () { r.parseStops({ code: 401 }); }, function (e) { return e.kind === 'key'; });
});

test('distance, spans, formatting', function () {
  assert.ok(Math.abs(r.distanceMeters(40, -74, 41, -74) - 111195) < 200);
  assert.ok(Math.abs(r.searchSpans(805, 0).latSpan - 0.01446) < 0.0001);
  assert.strictEqual(r.formatDistance(50, false), '164 ft');
  assert.strictEqual(r.formatDistance(402, false), '0.2 mi');
  assert.strictEqual(r.formatDistance(50, true), '50 m');
  assert.strictEqual(r.formatDistance(402, true), '0.4 km');
});

test('fit keeps within C buffers without splitting characters', function () {
  assert.strictEqual(r.fit('B41', 10), 'B41');
  assert.strictEqual(r.fit('ABCDEFGHIJKL', 10), 'ABCDEFGHI');
  assert.strictEqual(r.fit('Café', 5), 'Caf');
  assert.strictEqual(r.fit(null, 10), '');
});

test('settings helpers', function () {
  assert.strictEqual(r.normalizeRadius('0.7'), 0.75);
  assert.strictEqual(r.normalizeRadius('x'), 0.5);
  assert.strictEqual(r.normalizeRadius(9), 1);
  assert.strictEqual(r.cleanApiKey(' "abc 123" '), 'abc123');
});

test('config page round-trips its values', function () {
  var url = config.configPageUrl({ apiKey: 'a"<b', radiusMiles: 0.75, metric: true }, r.RADIUS_OPTIONS_MILES);
  var html = decodeURIComponent(url.slice('data:text/html;charset=utf-8,'.length));
  assert.ok(html.indexOf('value="a&quot;&lt;b"') > 0);
  assert.ok(html.indexOf('<option value="0.75" selected>') > 0);
  var resp = encodeURIComponent(JSON.stringify({ apiKey: 'k', radiusMiles: 1, metric: false }));
  assert.deepStrictEqual(config.parseConfigResponse(resp), { apiKey: 'k', radiusMiles: 1, metric: false });
  assert.strictEqual(config.parseConfigResponse(''), null);
  assert.strictEqual(config.parseConfigResponse('CANCELLED'), null);
});

test('a bad key reported inside a 200 response is a key error', function () {
  var json = { Siri: { ServiceDelivery: { StopMonitoringDelivery: [{ ErrorCondition: {
    OtherError: { ErrorText: 'API key is not authorized.' } } }] } } };
  assert.throws(function () { r.parseJourneys(json); }, function (e) { return e.kind === 'key'; });
  var other = { Siri: { ServiceDelivery: { StopMonitoringDelivery: [{ ErrorCondition: { Description: 'No such stop' } }] } } };
  assert.deepStrictEqual(r.parseJourneys(other), []);
});
