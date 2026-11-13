// The settings page shown in the Pebble phone app ("Settings" on this app).
// Built as a data: URL so there is no web page to host. It returns its values via pebblejs://close#.

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function configPageUrl(settings, radiusOptions) {
  var options = radiusOptions.map(function (miles) {
    var selected = miles === settings.radiusMiles ? ' selected' : '';
    return '<option value="' + miles + '"' + selected + '>' + miles + ' mi</option>';
  }).join('');

  var html = '<!DOCTYPE html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>NYC Buses settings</title><style>' +
    'body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:16px}' +
    'h1{font-size:20px;color:#5C9CE6}label{display:block;margin:18px 0 6px;font-weight:bold}' +
    'input,select{width:100%;box-sizing:border-box;padding:10px;font-size:16px;border-radius:6px;border:1px solid #444;background:#222;color:#eee}' +
    '.row{display:flex;align-items:center;gap:10px;margin-top:18px}.row input{width:auto}' +
    'button{margin-top:24px;width:100%;padding:12px;font-size:17px;border:0;border-radius:6px;background:#5C9CE6;color:#000;font-weight:bold}' +
    'p{color:#aaa;font-size:13px}a{color:#FFD166}' +
    '</style></head><body>' +
    '<h1>NYC Buses On A Glance</h1>' +
    '<label for="key">MTA Bus Time API key</label>' +
    '<input id="key" autocomplete="off" autocapitalize="off" spellcheck="false" value="' + escapeHtml(settings.apiKey) + '">' +
    '<p>Free from <a href="https://register.developer.obanyc.com/">register.developer.obanyc.com</a>. It stays on your phone.</p>' +
    '<label for="radius">Search radius</label><select id="radius">' + options + '</select>' +
    '<div class="row"><input type="checkbox" id="metric"' + (settings.metric ? ' checked' : '') + '>' +
    '<label for="metric" style="margin:0">Show distances in km</label></div>' +
    '<button id="save">Save</button>' +
    '<script>document.getElementById("save").onclick=function(){' +
    'var v={apiKey:document.getElementById("key").value,' +
    'radiusMiles:Number(document.getElementById("radius").value),' +
    'metric:document.getElementById("metric").checked};' +
    'document.location="pebblejs://close#"+encodeURIComponent(JSON.stringify(v));};</script>' +
    '</body></html>';

  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

/** The page's response, or null if it was cancelled or unreadable. */
function parseConfigResponse(response) {
  if (!response) return null;
  try {
    var raw = response;
    try { raw = decodeURIComponent(response); } catch (e) { /* already decoded */ }
    var value = JSON.parse(raw);
    return value && typeof value === 'object' ? value : null;
  } catch (e) {
    return null;
  }
}

module.exports = { configPageUrl: configPageUrl, parseConfigResponse: parseConfigResponse };
