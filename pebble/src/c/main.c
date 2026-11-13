// NYC Buses On A Glance for Pebble.
// The watch only draws: the phone (src/pkjs) finds stops and arrivals and sends a short list.
// Arrival times arrive as Unix timestamps, so "N min" is recomputed every minute on the watch.

#include <pebble.h>

#define MAX_ITEMS 12   // must match MAX_ITEMS in index.js
#define ROUTE_LEN 10   // buffer sizes must match SIZES in index.js
#define DEST_LEN 32
#define STOP_LEN 32
#define DIST_LEN 12
#define AWAY_LEN 24
#define STATUS_LEN 64
#define MAX_TIMES 3
#define DEPARTED_GRACE_SECONDS 60

typedef struct {
  char route[ROUTE_LEN];
  char dest[DEST_LEN];
  char stop[STOP_LEN];
  char dist[DIST_LEN];
  char away[AWAY_LEN];
  int32_t times[MAX_TIMES];
  bool starred;
} Item;

static Item s_items[MAX_ITEMS];
static int s_count = 0;
static char s_status[STATUS_LEN] = "Finding nearby buses...";

static Window *s_main_window;
static MenuLayer *s_menu;

static Window *s_detail_window;
static ScrollLayer *s_detail_scroll;
static TextLayer *s_detail_title;
static TextLayer *s_detail_body;
// The detail screen shows a copy, so a list refresh (every minute) doesn't close or blank it.
static Item s_detail_item;
static char s_detail_title_text[ROUTE_LEN + 4];
static char s_detail_body_text[256];

// ---------------------------------------------------------------------------
// Formatting

/** Whole minutes until t, rounded, never negative. -1 if t is unset or the bus has left. */
static int minutes_until(int32_t t) {
  if (t <= 0) return -1;
  int32_t now = (int32_t)time(NULL);
  if (t < now - DEPARTED_GRACE_SECONDS) return -1;
  int32_t minutes = (t - now + 30) / 60;
  return minutes < 0 ? 0 : (int)minutes;
}

/** Advances *used after an snprintf into buf, never past the end of the buffer. */
static void advance(size_t *used, int written, size_t size) {
  if (written > 0) *used += (size_t)written;
  if (*used >= size) *used = size - 1;
}

/** "3, 11 min", "Now, 7 min", "Now" or "-" into buf. */
static void format_etas(const Item *item, char *buf, size_t size) {
  buf[0] = '\0';
  size_t used = 0;
  int shown = 0;
  bool last_was_number = false;
  for (int i = 0; i < MAX_TIMES && shown < 2; i++) {
    int m = minutes_until(item->times[i]);
    if (m < 0) continue;
    const char *sep = shown ? ", " : "";
    if (m == 0) {
      advance(&used, snprintf(buf + used, size - used, "%sNow", sep), size);
    } else {
      advance(&used, snprintf(buf + used, size - used, "%s%d", sep, m), size);
    }
    shown++;
    last_was_number = m > 0;
  }
  if (shown == 0) {
    snprintf(buf, size, "-");
  } else if (last_was_number) {
    snprintf(buf + used, size - used, " min");
  }
}

static bool same_item(const Item *a, const Item *b) {
  return strcmp(a->route, b->route) == 0 && strcmp(a->dest, b->dest) == 0 &&
         strcmp(a->stop, b->stop) == 0;
}

/** Index of the detail screen's bus in the current list, or -1. */
static int detail_index(void) {
  for (int i = 0; i < s_count; i++) {
    if (same_item(&s_items[i], &s_detail_item)) return i;
  }
  return -1;
}

static void format_clock(int32_t t, char *buf, size_t size) {
  time_t when = (time_t)t;
  struct tm *local = localtime(&when);
  strftime(buf, size, clock_is_24h_style() ? "%H:%M" : "%I:%M %p", local);
  if (!clock_is_24h_style() && buf[0] == '0') {
    memmove(buf, buf + 1, strlen(buf)); // "03:05 PM" -> "3:05 PM"
  }
}

// ---------------------------------------------------------------------------
// Talking to the phone

static void send_int(uint32_t key, int32_t value) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) return; // busy: the next tick will retry
  dict_write_int32(iter, key, value);
  app_message_outbox_send();
}

static void request_refresh(void) {
  send_int(MESSAGE_KEY_REQUEST, 1);
}

static void toggle_star(int index) {
  if (index < 0 || index >= s_count) return;
  s_items[index].starred = !s_items[index].starred; // instant feedback; the phone re-sorts
  vibes_short_pulse();
  send_int(MESSAGE_KEY_TOGGLE_STAR, index);
}

static void copy_string(DictionaryIterator *iter, uint32_t key, char *dest, size_t size) {
  Tuple *t = dict_find(iter, key);
  if (t && t->type == TUPLE_CSTRING) {
    strncpy(dest, t->value->cstring, size - 1);
    dest[size - 1] = '\0';
  }
}

static int32_t read_int(DictionaryIterator *iter, uint32_t key, int32_t fallback) {
  Tuple *t = dict_find(iter, key);
  if (!t) return fallback;
  switch (t->type) {
    case TUPLE_INT:
    case TUPLE_UINT:
      if (t->length == 1) return t->value->int8;
      if (t->length == 2) return t->value->int16;
      return t->value->int32;
    default:
      return fallback;
  }
}

static void update_detail(void);

static void inbox_received(DictionaryIterator *iter, void *context) {
#if defined(DEMO_DATA)
  return; // keep the sample list on screen
#endif
  Tuple *status = dict_find(iter, MESSAGE_KEY_STATUS);
  if (status && status->type == TUPLE_CSTRING) {
    strncpy(s_status, status->value->cstring, STATUS_LEN - 1);
    s_status[STATUS_LEN - 1] = '\0';
  }

  Tuple *count = dict_find(iter, MESSAGE_KEY_COUNT);
  if (count) {
    // A new list is starting; its items follow one message each.
    s_count = 0;
    s_status[0] = '\0';
  }

  int32_t index = read_int(iter, MESSAGE_KEY_INDEX, -1);
  if (index >= 0 && index < MAX_ITEMS) {
    Item *item = &s_items[index];
    memset(item, 0, sizeof(Item));
    copy_string(iter, MESSAGE_KEY_ROUTE, item->route, ROUTE_LEN);
    copy_string(iter, MESSAGE_KEY_DEST, item->dest, DEST_LEN);
    copy_string(iter, MESSAGE_KEY_STOP, item->stop, STOP_LEN);
    copy_string(iter, MESSAGE_KEY_DIST, item->dist, DIST_LEN);
    copy_string(iter, MESSAGE_KEY_AWAY, item->away, AWAY_LEN);
    item->times[0] = read_int(iter, MESSAGE_KEY_T1, 0);
    item->times[1] = read_int(iter, MESSAGE_KEY_T2, 0);
    item->times[2] = read_int(iter, MESSAGE_KEY_T3, 0);
    item->starred = read_int(iter, MESSAGE_KEY_STARRED, 0) != 0;
    if (index + 1 > s_count) s_count = index + 1;
    if (s_detail_window && same_item(item, &s_detail_item)) s_detail_item = *item;
  }

  if (s_menu) menu_layer_reload_data(s_menu);
  update_detail();
}

// ---------------------------------------------------------------------------
// Main list

static uint16_t get_num_rows(MenuLayer *menu, uint16_t section, void *context) {
  return s_count > 0 ? (uint16_t)s_count : 1; // one status row when the list is empty
}

static int16_t get_header_height(MenuLayer *menu, uint16_t section, void *context) {
  return PBL_IF_ROUND_ELSE(28, MENU_CELL_BASIC_HEADER_HEIGHT);
}

static void draw_header(GContext *ctx, const Layer *cell, uint16_t section, void *context) {
  const char *title = (s_count > 0 && s_status[0]) ? s_status : "NYC Buses";
#if defined(PBL_ROUND)
  // Centred and pushed down: the default header is left-aligned and gets cut by the circle.
  GRect bounds = layer_get_bounds(cell);
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, title, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(bounds.size.w / 6, 8, bounds.size.w * 2 / 3, 18),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
#else
  menu_cell_basic_header_draw(ctx, cell, title);
#endif
}

static int16_t get_cell_height(MenuLayer *menu, MenuIndex *index, void *context) {
  if (s_count == 0) return PBL_IF_ROUND_ELSE(120, 110);
#if defined(PBL_ROUND)
  return MENU_CELL_ROUND_FOCUSED_TALL_CELL_HEIGHT;
#elif PBL_DISPLAY_HEIGHT >= 200
  return 58; // Pebble Time 2: bigger system fonts need taller rows
#else
  return 44;
#endif
}

static void draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *context) {
  if (s_count == 0) {
    GRect bounds = layer_get_bounds(cell);
    GRect box = grect_inset(bounds, GEdgeInsets(6, PBL_IF_ROUND_ELSE(18, 6)));
    const char *text = s_status[0] ? s_status : "Finding nearby buses...";
    graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), box,
                       GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
    return;
  }
  if (index->row >= s_count) return;

  const Item *item = &s_items[index->row];
  char etas[24];
  format_etas(item, etas, sizeof(etas));
  char title[ROUTE_LEN + 32];
  snprintf(title, sizeof(title), "%s%s  %s", item->starred ? "* " : "", item->route, etas);
  char subtitle[DEST_LEN + 4];
  snprintf(subtitle, sizeof(subtitle), "to %s", item->dest);
  menu_cell_basic_draw(ctx, cell, title, subtitle, NULL);
}

static void open_detail(int index);

static void select_click(MenuLayer *menu, MenuIndex *index, void *context) {
  if (s_count == 0) {
    snprintf(s_status, STATUS_LEN, "Finding nearby buses...");
    menu_layer_reload_data(s_menu);
    request_refresh();
    return;
  }
  open_detail(index->row);
}

static void select_long_click(MenuLayer *menu, MenuIndex *index, void *context) {
  if (s_count == 0) return;
  toggle_star(index->row);
  menu_layer_reload_data(s_menu);
}

static void main_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = get_num_rows,
    .get_header_height = get_header_height,
    .draw_header = draw_header,
    .get_cell_height = get_cell_height,
    .draw_row = draw_row,
    .select_click = select_click,
    .select_long_click = select_long_click,
  });
#if defined(PBL_COLOR)
  menu_layer_set_highlight_colors(s_menu, GColorPictonBlue, GColorBlack);
#endif
  menu_layer_set_click_config_onto_window(s_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void main_window_unload(Window *window) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

// ---------------------------------------------------------------------------
// Detail screen: one route and direction

static void build_detail_text(const Item *item) {
  snprintf(s_detail_title_text, sizeof(s_detail_title_text), "%s%s",
           item->starred ? "* " : "", item->route);

  // Times first: they're what you look at. Then where the bus goes and which stop.
  char *buf = s_detail_body_text;
  const size_t size = sizeof(s_detail_body_text);
  size_t used = 0;
  buf[0] = '\0';
  bool any = false;
  for (int i = 0; i < MAX_TIMES; i++) {
    int m = minutes_until(item->times[i]);
    if (m < 0) continue;
    char clock[12];
    format_clock(item->times[i], clock, sizeof(clock));
    const char *sep = any ? "\n" : "";
    if (m == 0) {
      advance(&used, snprintf(buf + used, size - used, "%sNow (%s)", sep, clock), size);
    } else {
      advance(&used, snprintf(buf + used, size - used, "%s%d min (%s)", sep, m, clock), size);
    }
    any = true;
  }
  if (!any) {
    advance(&used, snprintf(buf + used, size - used, "No predictions right now"), size);
  }
  if (item->away[0]) {
    advance(&used, snprintf(buf + used, size - used, "\nNext bus %s", item->away), size);
  }
  advance(&used, snprintf(buf + used, size - used, "\n\nto %s\nat %s, %s", item->dest, item->stop,
                          item->dist), size);
  snprintf(buf + used, size - used, "%s",
           item->starred ? "\n\nHold SELECT to unstar" : "\n\nHold SELECT to star");
}

static void layout_detail(void) {
  if (!s_detail_window || !s_detail_title) return;
  build_detail_text(&s_detail_item);
  text_layer_set_text(s_detail_title, s_detail_title_text);
  text_layer_set_text(s_detail_body, s_detail_body_text);

  GRect bounds = layer_get_bounds(window_get_root_layer(s_detail_window));
  int16_t inset = PBL_IF_ROUND_ELSE(bounds.size.w / 8, 4);
  int16_t width = bounds.size.w - 2 * inset;
  int16_t top = PBL_IF_ROUND_ELSE(bounds.size.h / 8, 2);

  layer_set_frame(text_layer_get_layer(s_detail_title), GRect(inset, top, width, 2000));
  GSize title_size = text_layer_get_content_size(s_detail_title);
  layer_set_frame(text_layer_get_layer(s_detail_title), GRect(inset, top, width, title_size.h + 4));

  int16_t body_top = top + title_size.h + 6;
  layer_set_frame(text_layer_get_layer(s_detail_body), GRect(inset, body_top, width, 2000));
  GSize body_size = text_layer_get_content_size(s_detail_body);
  layer_set_frame(text_layer_get_layer(s_detail_body), GRect(inset, body_top, width, body_size.h + 4));

  scroll_layer_set_content_size(s_detail_scroll,
                                GSize(bounds.size.w, body_top + body_size.h + PBL_IF_ROUND_ELSE(bounds.size.h / 4, 12)));
}

static void update_detail(void) {
  layout_detail();
}

static void detail_long_select(ClickRecognizerRef recognizer, void *context) {
  int index = detail_index();
  if (index < 0) return; // this bus dropped off the latest list
  toggle_star(index);
  s_detail_item.starred = s_items[index].starred;
  layout_detail();
}

static void detail_click_config(void *context) {
  window_long_click_subscribe(BUTTON_ID_SELECT, 500, detail_long_select, NULL);
}

static TextLayer *make_text_layer(const char *font_key) {
  TextLayer *layer = text_layer_create(GRect(0, 0, 10, 10));
  text_layer_set_font(layer, fonts_get_system_font(font_key));
  text_layer_set_overflow_mode(layer, GTextOverflowModeWordWrap);
  text_layer_set_text_alignment(layer, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));
  text_layer_set_background_color(layer, GColorClear);
  return layer;
}

static void detail_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_detail_scroll = scroll_layer_create(layer_get_bounds(root));
  scroll_layer_set_callbacks(s_detail_scroll, (ScrollLayerCallbacks) {
    .click_config_provider = detail_click_config,
  });
  scroll_layer_set_click_config_onto_window(s_detail_scroll, window);

  s_detail_title = make_text_layer(FONT_KEY_GOTHIC_24_BOLD);
  s_detail_body = make_text_layer(FONT_KEY_GOTHIC_18);
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_detail_title, GColorPictonBlue);
#endif
  scroll_layer_add_child(s_detail_scroll, text_layer_get_layer(s_detail_title));
  scroll_layer_add_child(s_detail_scroll, text_layer_get_layer(s_detail_body));
  layer_add_child(root, scroll_layer_get_layer(s_detail_scroll));
  layout_detail();
}

static void detail_window_unload(Window *window) {
  text_layer_destroy(s_detail_title);
  text_layer_destroy(s_detail_body);
  scroll_layer_destroy(s_detail_scroll);
  s_detail_title = NULL;
  s_detail_body = NULL;
  s_detail_scroll = NULL;
  window_destroy(window);
  s_detail_window = NULL;
}

static void open_detail(int index) {
  if (index < 0 || index >= s_count || s_detail_window) return;
  s_detail_item = s_items[index];
  s_detail_window = window_create();
  window_set_window_handlers(s_detail_window, (WindowHandlers) {
    .load = detail_window_load,
    .unload = detail_window_unload,
  });
  window_stack_push(s_detail_window, true);
}

// ---------------------------------------------------------------------------
// App lifecycle

static void tick(struct tm *now, TimeUnits changed) {
  // Redraw the minutes, and ask the phone for fresh predictions while the app is open.
  if (s_menu) menu_layer_reload_data(s_menu);
  layout_detail();
  request_refresh();
}

#if defined(DEMO_DATA)
// Sample list for emulator screenshots (CI builds with PEBBLE_DEMO=1). Never in real builds.
static void load_demo_data(void) {
  int32_t now = (int32_t)time(NULL);
  const char *routes[] = { "B41", "B44-SBS", "M15", "Bx12" };
  const char *dests[] = { "DOWNTOWN BKLYN", "SHEEPSHEAD BAY", "SOUTH FERRY", "PELHAM BAY PK" };
  const int32_t first[] = { 180, 0, 420, 600 };
  for (int i = 0; i < 4; i++) {
    Item *item = &s_items[i];
    snprintf(item->route, ROUTE_LEN, "%s", routes[i]);
    snprintf(item->dest, DEST_LEN, "%s", dests[i]);
    snprintf(item->stop, STOP_LEN, "Flatbush Av / Church Av");
    snprintf(item->dist, DIST_LEN, "0.1 mi");
    snprintf(item->away, AWAY_LEN, "2 stops away");
    item->times[0] = now + first[i];
    item->times[1] = now + first[i] + 660;
    item->times[2] = now + first[i] + 1500;
    item->starred = i == 0;
  }
  s_count = 4;
  s_status[0] = '\0';
}
#endif

static void init(void) {
#if defined(DEMO_DATA)
  load_demo_data();
#endif
  app_message_register_inbox_received(inbox_received);
  app_message_open(512, 64);

  s_main_window = window_create();
  window_set_window_handlers(s_main_window, (WindowHandlers) {
    .load = main_window_load,
    .unload = main_window_unload,
  });
  window_stack_push(s_main_window, true);

  // The phone starts a refresh by itself when the app opens ("ready"), so none is sent here.
  tick_timer_service_subscribe(MINUTE_UNIT, tick);
}

static void deinit(void) {
  tick_timer_service_unsubscribe();
  window_destroy(s_main_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
