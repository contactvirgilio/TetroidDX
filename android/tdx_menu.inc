/* ---- Tetroid DX menu (inserted by android/patch_project.py) ----
 * Cannoli-style menu, matching the DKC1/DKC2 builds: black ~89% backdrop,
 * berry title, grey rows, teal pill on the selected row, gamepad hints.
 * Every size is a multiple of v = shorter screen side / 7.
 * Replaces the runtime's own settings menu. While it is open the game is
 * paused (tdx_menu_loop) and held buttons are released on close. */

static bool handle_binding_capture_event(const SDL_Event* event);
static void render_frame_internal(const uint32_t* framebuffer, bool count_guest_frame);
static bool handle_runtime_event(const SDL_Event* event, GBContext* ctx);

enum { TDX_PAGE_MAIN = 0, TDX_PAGE_CONTROLS = 1, TDX_PAGE_CREDITS = 2 };
static int g_tdx_menu_page = TDX_PAGE_MAIN;
static bool g_tdx_menu_focus_first = true;
static ImFont* g_tdx_menu_font = NULL;
static void* g_tdx_menu_font_data = NULL;
static const float TDX_FONT_PX = 64.0f;

/* Loads BPreplayBold.otf from the APK assets (SDL reads assets on Android). */
static void tdx_load_menu_font(void) {
    size_t size = 0;
    void* data = SDL_LoadFile("BPreplayBold.otf", &size);
    if (!data || size == 0) {
        fprintf(stderr, "[TDX] Menu font not found; using the default font\n");
        return;
    }
    ImFontConfig cfg;
    cfg.FontDataOwnedByAtlas = false;   /* we keep the buffer alive */
    g_tdx_menu_font = ImGui::GetIO().Fonts->AddFontFromMemoryTTF(data, (int)size, TDX_FONT_PX, &cfg);
    if (g_tdx_menu_font) {
        g_tdx_menu_font_data = data;
        ImGui::GetIO().FontDefault = g_tdx_menu_font;
    } else {
        SDL_free(data);
    }
}

/* Teal confirmation pill, drawn by the Android touch overlay (TouchControls.pill). */
static void tdx_pill(const char* msg) {
#if defined(__ANDROID__)
    JNIEnv* env = (JNIEnv*)SDL_AndroidGetJNIEnv();
    if (!env || !msg) return;
    jclass cls = env->FindClass("org/tetrisdx/recomp/TouchControls");
    if (!cls) { env->ExceptionClear(); return; }
    jmethodID m = env->GetStaticMethodID(cls, "pill", "(Ljava/lang/String;)V");
    if (!m) { env->ExceptionClear(); env->DeleteLocalRef(cls); return; }
    jstring s = env->NewStringUTF(msg);
    env->CallStaticVoidMethod(cls, m, s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(s);
    env->DeleteLocalRef(cls);
#else
    fprintf(stderr, "[TDX] %s\n", msg ? msg : "");
#endif
}

static void tdx_close_menu(void) {
    g_show_menu = false;
    g_tdx_menu_page = TDX_PAGE_MAIN;
    g_tdx_menu_focus_first = true;
    if (g_binding_capture_active) cancel_binding_capture();
}

static void tdx_menu_back(void) {
    if (g_tdx_menu_page != TDX_PAGE_MAIN) {
        g_tdx_menu_page = TDX_PAGE_MAIN;
        g_tdx_menu_focus_first = true;
    } else {
        tdx_close_menu();
    }
}

/* ---- PATCHES card, quick buttons and gamepad shortcuts (Nov 2026) ----
 * The main menu is now the Java PATCHES card drawn by the touch overlay (the
 * same style as the DKC STATES card). Java reads and changes the options
 * through nativeGet/nativeSet and asks for the menu to open, close or show a
 * list page through nativeRequest; the game thread applies those requests at
 * the start of each frame. Controls and Credits stay as ImGui list pages. */
static std::atomic<int> g_tdx_req{0};
enum { TDX_REQ_OPEN = 1, TDX_REQ_CLOSE = 2, TDX_REQ_CONTROLS = 4, TDX_REQ_CREDITS = 8, TDX_REQ_SAVE = 16 };

static void tdx_process_requests(void) {
    const int r = g_tdx_req.exchange(0);
    if (!r) return;
    if (r & TDX_REQ_SAVE) save_runtime_preferences();
    if (r & TDX_REQ_CLOSE) tdx_close_menu();
    if (r & TDX_REQ_OPEN) { g_tdx_menu_page = TDX_PAGE_MAIN; g_show_menu = true; }
    if (r & TDX_REQ_CONTROLS) { g_tdx_menu_page = TDX_PAGE_CONTROLS; g_show_menu = true; }
    if (r & TDX_REQ_CREDITS) { g_tdx_menu_page = TDX_PAGE_CREDITS; g_show_menu = true; }
}

/* Centre feedback card (title, value, one bar per choice), drawn by the overlay. */
static void tdx_card(const char* title, const char* value, int index, int count) {
#if defined(__ANDROID__)
    JNIEnv* env = (JNIEnv*)SDL_AndroidGetJNIEnv();
    if (!env) return;
    jclass cls = env->FindClass("org/tetrisdx/recomp/TouchControls");
    if (!cls) { env->ExceptionClear(); return; }
    jmethodID m = env->GetStaticMethodID(cls, "card", "(Ljava/lang/String;Ljava/lang/String;II)V");
    if (!m) { env->ExceptionClear(); env->DeleteLocalRef(cls); return; }
    jstring t = env->NewStringUTF(title);
    jstring v = env->NewStringUTF(value);
    env->CallStaticVoidMethod(cls, m, t, v, (jint)index, (jint)count);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(t);
    env->DeleteLocalRef(v);
    env->DeleteLocalRef(cls);
#else
    fprintf(stderr, "[TDX] %s: %s (%d/%d)\n", title, value, index + 1, count);
#endif
}

/* Lemuroid-style gamepad shortcuts: buttons held together. Stored as
 * tdx.sc0..tdx.sc3 = SDL controller button numbers, comma separated.
 * 0 Menu (empty = built-in Guide / L3 / Select+Down), 1 Toggle Hard Drop,
 * 2 Cycle Ghost Piece, 3 Cycle Palette. */
/* TDX_SC_COUNT and the helpers' declarations are in tdx_platform.inc. */
static int g_tdx_sc[TDX_SC_COUNT][3];
static int g_tdx_sc_n[TDX_SC_COUNT] = {0, 0, 0, 0};
static int g_tdx_sc_cap = -1;                 /* shortcut being recorded, or -1 */
static int g_tdx_cap_set[3];
static int g_tdx_cap_n = 0;
static bool g_tdx_pad_held[SDL_CONTROLLER_BUTTON_MAX];
static int g_tdx_sc_swallow = -1;             /* button whose release the game must not see */
static const char* const kTdxShortcutNames[TDX_SC_COUNT] = {
    "Menu shortcut", "Hard Drop shortcut", "Ghost shortcut", "Palette shortcut"};

static void tdx_sc_parse(int i, const char* value) {
    g_tdx_sc_n[i] = 0;
    const char* p = value;
    while (p && *p && g_tdx_sc_n[i] < 3) {
        char* end = NULL;
        long b = strtol(p, &end, 10);
        if (end == p) break;
        if (b >= 0 && b < SDL_CONTROLLER_BUTTON_MAX) g_tdx_sc[i][g_tdx_sc_n[i]++] = (int)b;
        p = (*end == ',') ? end + 1 : end;
    }
}

static std::string tdx_sc_value(int i) {
    std::string out;
    for (int k = 0; k < g_tdx_sc_n[i]; ++k) {
        if (k) out += ",";
        out += std::to_string(g_tdx_sc[i][k]);
    }
    return out;
}

static std::string tdx_sc_label(int i) {
    if (g_tdx_sc_cap == i) return "press buttons...";
    if (g_tdx_sc_n[i] == 0) return i == 0 ? "Guide / L3" : "None";
    std::string out;
    for (int k = 0; k < g_tdx_sc_n[i]; ++k) {
        if (k) out += " + ";
        out += binding_display_label(make_binding(GB_INPUT_BINDING_CONTROLLER_BUTTON, g_tdx_sc[i][k]));
    }
    return out;
}

static void tdx_fire_shortcut(int i) {
    static const char* const ghost[3] = {"Off", "Light", "Dark"};
    static const char* const pal[TDX_PAL_COUNT] = {"Original", "GBC LCD", "Pastel", "Swamp", "Sunset"};
    switch (i) {
        case 0:
            g_tdx_menu_page = TDX_PAGE_MAIN;
            g_show_menu = true;
            return;
        case 1:
            g_tdx_harddrop = !g_tdx_harddrop;
            tdx_card("HARD DROP", g_tdx_harddrop ? "On" : "Off", g_tdx_harddrop ? 1 : 0, 2);
            break;
        case 2:
            g_tdx_ghost = (g_tdx_ghost + 1) % 3;
            tdx_card("GHOST PIECE", ghost[g_tdx_ghost], g_tdx_ghost, 3);
            break;
        default:
            g_tdx_palette = (g_tdx_palette + 1) % TDX_PAL_COUNT;
            tdx_card("PALETTE", pal[g_tdx_palette], g_tdx_palette, TDX_PAL_COUNT);
            break;
    }
    save_runtime_preferences();
}

/* Sees every controller button event. Returns true if the game must not get it.
 * allow_fire is false inside the menu (only recording and held tracking there). */
static bool tdx_shortcut_event(const SDL_Event* e, bool allow_fire) {
    if (e->type != SDL_CONTROLLERBUTTONDOWN && e->type != SDL_CONTROLLERBUTTONUP) return false;
    const int b = e->cbutton.button;
    if (b < 0 || b >= SDL_CONTROLLER_BUTTON_MAX) return false;
    const bool down = e->type == SDL_CONTROLLERBUTTONDOWN;
    g_tdx_pad_held[b] = down;
    if (g_tdx_sc_cap >= 0) {   /* recording: collect buttons until all are let go */
        if (down && g_tdx_cap_n < 3) {
            bool dup = false;
            for (int k = 0; k < g_tdx_cap_n; ++k) if (g_tdx_cap_set[k] == b) dup = true;
            if (!dup) g_tdx_cap_set[g_tdx_cap_n++] = b;
        }
        if (!down && g_tdx_cap_n > 0) {
            bool any = false;
            for (int k = 0; k < SDL_CONTROLLER_BUTTON_MAX; ++k) if (g_tdx_pad_held[k]) any = true;
            if (!any) {
                for (int k = 0; k < g_tdx_cap_n; ++k) g_tdx_sc[g_tdx_sc_cap][k] = g_tdx_cap_set[k];
                g_tdx_sc_n[g_tdx_sc_cap] = g_tdx_cap_n;
                g_tdx_sc_cap = -1;
                g_tdx_cap_n = 0;
                save_runtime_preferences();
            }
        }
        return true;
    }
    if (!down) {
        if (g_tdx_sc_swallow == b) { g_tdx_sc_swallow = -1; return true; }
        return false;
    }
    if (!allow_fire) return false;
    for (int i = 0; i < TDX_SC_COUNT; ++i) {
        const int n = g_tdx_sc_n[i];
        if (n == 0) continue;
        bool has = false, all = true;
        for (int k = 0; k < n; ++k) {
            if (g_tdx_sc[i][k] == b) has = true;
            if (!g_tdx_pad_held[g_tdx_sc[i][k]]) all = false;
        }
        if (has && all) {
            g_tdx_sc_swallow = b;   /* the press that completed the combo */
            tdx_fire_shortcut(i);
            return true;
        }
    }
    return false;
}

#if defined(__ANDROID__)
/* Java side of the options. ids: 0 Hard Drop, 1 Ghost, 2 Don't Panic,
 * 3 Skip Celebrations, 4 Palette, 5 Scanlines, 6 Mute, 7 menu page (-1 closed),
 * 8 Korobeiniki, 9 SRS, 10 Randomizer. */
extern "C" JNIEXPORT jint JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeGet(JNIEnv* env, jclass cls, jint id) {
    (void)env; (void)cls;
    switch (id) {
        case 0: return g_tdx_harddrop ? 1 : 0;
        case 1: return g_tdx_ghost;
        case 2: return g_tdx_dontpanic ? 1 : 0;
        case 3: return g_tdx_skipceleb ? 1 : 0;
        case 4: return g_tdx_palette;
        case 5: return g_tdx_scanlines ? 1 : 0;
        case 6: return g_audio_muted.load(std::memory_order_relaxed) ? 1 : 0;
        case 7: return g_show_menu ? g_tdx_menu_page : -1;
        case 8: return g_tdx_korobeiniki ? 1 : 0;
        case 9: return g_tdx_srs ? 1 : 0;
        case 10: return g_tdx_randomizer;
        default: return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeSet(JNIEnv* env, jclass cls, jint id, jint v) {
    (void)env; (void)cls;
    switch (id) {
        case 0: g_tdx_harddrop = v != 0; break;
        case 1: g_tdx_ghost = (v >= 0 && v <= 2) ? (int)v : 0; break;
        case 2: g_tdx_dontpanic = v != 0; break;
        case 3: g_tdx_skipceleb = v != 0; break;
        case 4: g_tdx_palette = (v >= 0 && v < TDX_PAL_COUNT) ? (int)v : TDX_PAL_GBC_LCD; break;
        case 5: g_tdx_scanlines = v != 0; break;
        case 6: g_audio_muted.store(v != 0, std::memory_order_relaxed); break;
        case 8: g_tdx_korobeiniki = v != 0; break;
        case 9: g_tdx_srs = v != 0; break;
        case 10: g_tdx_randomizer = (v >= 0 && v <= 2) ? (int)v : 0; break;
        default: return;
    }
    g_tdx_req.fetch_or(TDX_REQ_SAVE);
}

extern "C" JNIEXPORT void JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeRequest(JNIEnv* env, jclass cls, jint bits) {
    (void)env; (void)cls;
    g_tdx_req.fetch_or((int)bits);
}
#endif

static const char* const kTdxGhostNames[3] = {"Off", "Light", "Dark"};
static const char* const kTdxPaletteNames[TDX_PAL_COUNT] = {
    "Original", "GBC LCD", "Pastel", "Swamp", "Sunset"};
static const GBInputAction kTdxControlActions[8] = {
    GB_INPUT_ACTION_UP, GB_INPUT_ACTION_DOWN, GB_INPUT_ACTION_LEFT, GB_INPUT_ACTION_RIGHT,
    GB_INPUT_ACTION_A, GB_INPUT_ACTION_B, GB_INPUT_ACTION_SELECT, GB_INPUT_ACTION_START};
static const char* const kTdxControlNames[8] = {
    "Up", "Down", "Left", "Right", "A", "B", "Select", "Start"};

/* Changes a main-menu value row by dir (+1 / -1). Returns true if it was a value row. */
static bool tdx_change_main(int row, int dir) {
    char msg[64];
    switch (row) {
        case 0: g_tdx_harddrop = !g_tdx_harddrop;
            snprintf(msg, sizeof msg, "Hard Drop %s", g_tdx_harddrop ? "on" : "off"); break;
        case 1: g_tdx_ghost = (g_tdx_ghost + (dir > 0 ? 1 : 2)) % 3;
            snprintf(msg, sizeof msg, "Ghost Piece: %s", kTdxGhostNames[g_tdx_ghost]); break;
        case 2: g_tdx_dontpanic = !g_tdx_dontpanic;
            snprintf(msg, sizeof msg, "Don't Panic %s", g_tdx_dontpanic ? "on" : "off"); break;
        case 3: g_tdx_skipceleb = !g_tdx_skipceleb;
            snprintf(msg, sizeof msg, "Skip Celebrations %s", g_tdx_skipceleb ? "on" : "off"); break;
        case 4: g_tdx_palette = (g_tdx_palette + (dir > 0 ? 1 : TDX_PAL_COUNT - 1)) % TDX_PAL_COUNT;
            snprintf(msg, sizeof msg, "Palette: %s", kTdxPaletteNames[g_tdx_palette]); break;
        case 5: g_tdx_scanlines = !g_tdx_scanlines;
            snprintf(msg, sizeof msg, "Scanlines %s", g_tdx_scanlines ? "on" : "off"); break;
        default: return false;
    }
    save_runtime_preferences();
    (void)msg;   /* no pop-up: the menu row already shows the new value */
    return true;
}

static void tdx_draw_hints(const char* const* letters, const char* const* labels, int n,
                           float v, ImVec2 display) {
    ImDrawList* dl = ImGui::GetForegroundDrawList();
    const float ly = display.y - 1.0f * v, lh = 0.7f * v, rad = lh * 0.5f, ts = 0.34f * v;
    ImFont* font = ImGui::GetFont();
    /* First hint on the left, the rest from the right. */
    float xr = display.x - 0.3f * v;
    for (int k = 0; k < n; ++k) {
        const ImVec2 lsz = font->CalcTextSizeA(ts, 10000.0f, 0.0f, labels[k]);
        const float w = 2.0f * rad + 0.1f * v + lsz.x + 0.35f * v;
        float x0;
        if (k == 0) {
            x0 = 0.3f * v;
        } else {
            x0 = xr - w;
            xr = x0 - 0.2f * v;
        }
        dl->AddRectFilled(ImVec2(x0, ly), ImVec2(x0 + w, ly + lh), IM_COL32(14, 14, 36, 255), rad);
        dl->AddCircleFilled(ImVec2(x0 + rad, ly + rad), rad * 0.78f, IM_COL32(24, 26, 70, 255), 32);
        const ImVec2 lw = font->CalcTextSizeA(ts, 10000.0f, 0.0f, letters[k]);
        dl->AddText(font, ts, ImVec2(x0 + rad - lw.x * 0.5f, ly + rad - ts * 0.5f),
                    IM_COL32(61, 77, 181, 255), letters[k]);
        dl->AddText(font, ts, ImVec2(x0 + 2.0f * rad + 0.1f * v, ly + rad - ts * 0.5f),
                    IM_COL32(61, 77, 181, 255), labels[k]);
    }
}

/* One menu entry. Info rows (Credits text) are smaller, never selectable. */
typedef struct {
    char label[96];
    bool info;
} TdxMenuItem;

static float tdx_item_h(const TdxMenuItem* it, float v) {
    return it->info ? 0.50f * v : 0.86f * v;
}

/* Matches the DKC1/DKC2 menu (Oct 28 version):
 * - own selection and scrolling (no ImGui navigation); only whole rows are
 *   shown, always above the button hints, and the title stays fixed;
 * - a tap registers only on the label (the pill area), not the whole row;
 * - dragging anywhere on the list scrolls it, and a drag is never a tap;
 * - a scroll bar (tap or drag, 1v wide strip on the right) when the list is
 *   longer than the screen;
 * - the last row you used stays highlighted;
 * - hints only while a gamepad is in use; touching hides them. */
static void tdx_draw_menu(void) {
#if defined(__ANDROID__)
    /* On Android the main page is the Java PATCHES card drawn by the touch
     * overlay; ImGui only draws the Controls and Credits list pages. */
    if (g_tdx_menu_page == TDX_PAGE_MAIN) return;
#endif
    ImGuiIO& io = ImGui::GetIO();
    const ImVec2 display = io.DisplaySize;
    const float v = (display.x < display.y ? display.x : display.y) / 7.0f;
    io.FontGlobalScale = (0.46f * v) / (g_tdx_menu_font ? TDX_FONT_PX : 13.0f);
    const float pitch = 0.86f * v, pillH = 0.8f * v, rowTop0 = 1.35f * v;
    const ImU32 colSel = IM_COL32(0, 137, 123, 255);
    const ImU32 colHeld = IM_COL32(38, 166, 154, 255);
    const ImU32 colGrey = IM_COL32(184, 184, 184, 255);
    const ImU32 colWhite = IM_COL32(255, 255, 255, 255);

    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowSize(display, ImGuiCond_Always);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(0.0f, 0.0f));
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(0.0f, 0.0f));
    ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.0f, 0.0f, 0.0f, 228.0f / 255.0f));
    ImGui::PushStyleColor(ImGuiCol_NavHighlight, ImVec4(0.0f, 0.0f, 0.0f, 0.0f));
    ImGui::Begin("Tetroid DX Menu", nullptr,
                 ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoCollapse |
                     ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoTitleBar |
                     ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoMove |
                     ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoNavInputs |
                     ImGuiWindowFlags_NoNavFocus);
    ImDrawList* dl = ImGui::GetWindowDrawList();
    const float fh = ImGui::GetFontSize();

    /* Per-page selection and scroll position, kept while the menu is open. */
    static int s_sel[3] = {0, 0, 0}, s_first[3] = {0, 0, 0};
    static bool s_pad = false, s_lock = false, s_dragged = false;
    static int s_down_item = -1, s_down_first = 0, s_last_page = -1;
    if (ImGui::IsWindowAppearing()) {
        for (int k = 0; k < 3; ++k) { s_sel[k] = 0; s_first[k] = 0; }
        s_pad = false;
        s_lock = true;    /* ignore the buttons that opened the menu until released */
    }
    const int page = g_tdx_menu_page;
    if (page != s_last_page) {
        if (page != TDX_PAGE_MAIN) { s_sel[page] = 0; s_first[page] = 0; }
        s_last_page = page;
        s_lock = true;    /* the press that changed page must not act on the new page */
    }

    /* Gamepad in use? (hints on). Touching the screen turns them off. */
    bool anyPad = false;
    for (int k = (int)ImGuiKey_GamepadStart; k <= (int)ImGuiKey_GamepadRStickDown; ++k)
        if (ImGui::IsKeyDown((ImGuiKey)k)) anyPad = true;
    if (anyPad || ImGui::IsKeyPressed(ImGuiKey_DownArrow, false) ||
        ImGui::IsKeyPressed(ImGuiKey_UpArrow, false)) s_pad = true;
    if (io.MouseDown[0]) s_pad = false;
    /* While remapping, and until every button is let go afterwards, the menu
     * ignores the gamepad, so the button being mapped never also acts here. */
    if (g_binding_capture_active || g_tdx_sc_cap >= 0) s_lock = true;
    else if (s_lock && !anyPad) s_lock = false;
    const bool live = !s_lock;

    {
        const char* heading = page == TDX_PAGE_CONTROLS ? "Controls"
                            : page == TDX_PAGE_CREDITS  ? "Credits" : "Menu";
        const float tsz = 0.75f * v;
        ImGui::SetWindowFontScale(tsz / fh);
        ImGui::SetCursorScreenPos(ImVec2(0.5f * v, 0.7f * v - tsz * 0.5f));
        ImGui::TextColored(ImVec4(192 / 255.0f, 51 / 255.0f, 107 / 255.0f, 1.0f), "%s", heading);
        ImGui::SetWindowFontScale(1.0f);
    }

    /* ---- build this page's entries ---- */
    TdxMenuItem items[16];
    int n = 0;
    if (page == TDX_PAGE_MAIN) {
        snprintf(items[n].label, 96, "Hard Drop: %s", g_tdx_harddrop ? "On" : "Off"); items[n++].info = false;
        snprintf(items[n].label, 96, "Ghost Piece: %s", kTdxGhostNames[g_tdx_ghost % 3]); items[n++].info = false;
        snprintf(items[n].label, 96, "Don't Panic: %s", g_tdx_dontpanic ? "On" : "Off"); items[n++].info = false;
        snprintf(items[n].label, 96, "Skip Celebrations: %s", g_tdx_skipceleb ? "On" : "Off"); items[n++].info = false;
        snprintf(items[n].label, 96, "Palette: %s", kTdxPaletteNames[g_tdx_palette % TDX_PAL_COUNT]); items[n++].info = false;
        snprintf(items[n].label, 96, "Scanlines: %s", g_tdx_scanlines ? "On" : "Off"); items[n++].info = false;
        snprintf(items[n].label, 96, "Controls"); items[n++].info = false;
        snprintf(items[n].label, 96, "Credits"); items[n++].info = false;
        snprintf(items[n].label, 96, "Close"); items[n++].info = false;
    } else if (page == TDX_PAGE_CONTROLS) {
        for (int i = 0; i < 8; ++i) {
            const GBInputAction act = kTdxControlActions[i];
            if (g_binding_capture_active && g_binding_capture_action == act) {
                snprintf(items[n].label, 96, "%s: press a button...", kTdxControlNames[i]);
            } else {
                /* Show the main assignment only, so rows fit in portrait. */
                std::string b0 = binding_display_label(g_controller_bindings[act][0]);
                const size_t cut = b0.find(" / ");
                if (cut != std::string::npos) b0 = b0.substr(0, cut);
                snprintf(items[n].label, 96, "%s: %s", kTdxControlNames[i], b0.c_str());
            }
            items[n++].info = false;
        }
        for (int i = 0; i < TDX_SC_COUNT; ++i) {
            snprintf(items[n].label, 96, "%s: %s", kTdxShortcutNames[i], tdx_sc_label(i).c_str());
            items[n++].info = false;
        }
        snprintf(items[n].label, 96, "Reset to defaults"); items[n++].info = false;
        snprintf(items[n].label, 96, "Back"); items[n++].info = false;
    } else {
        static const char* const lines[] = {
            "Android port built with Claude (Anthropic)",
            "Hard drop & ghost piece: lightbulb-sun (MIT)",
            "  requested by SpongeDan; ghost idea by szechp",
            "Don't Panic: DevEd",
            "Skip Celebrations: llakssz",
            "Korobeiniki (Music Fix): Marc Robledo",
            "SRS: rotation data from tetris.wiki",
            "35-Bag (TGM-style): after the TGM3 randomizer",
            "Recompiler: GB Recompiled by arcanite24 (MIT)",
            "SDL2 (zlib), Dear ImGui (MIT), RGBDS",
            "Font: BPreplay (SIL Open Font License)",
            "Unofficial fan port. Tetris DX is a",
            "trademark of its respective owners.",
        };
        for (size_t k = 0; k < sizeof lines / sizeof lines[0]; ++k) {
            snprintf(items[n].label, 96, "%s", lines[k]);
            items[n++].info = true;
        }
        snprintf(items[n].label, 96, "Back"); items[n++].info = false;
    }

    int& sel = s_sel[page];
    int& first = s_first[page];
    if (sel < 0 || sel >= n || items[sel].info) {
        sel = 0;
        while (sel < n - 1 && items[sel].info) ++sel;
    }

    /* ---- visible window: whole rows only, from rowTop0 to above the hints ---- */
    const float areaTop = rowTop0, areaBot = display.y - 1.15f * v, area = areaBot - areaTop;
    float total = 0.0f;
    for (int i = 0; i < n; ++i) total += tdx_item_h(&items[i], v);
    int maxFirst = n - 1;
    {
        float h = 0.0f;
        for (int i = n - 1; i >= 0; --i) {
            h += tdx_item_h(&items[i], v);
            if (h > area) break;
            maxFirst = i;
        }
    }
    const bool scrolls = total > area + 0.5f;
    if (!scrolls) maxFirst = 0;

    /* ---- gamepad (and arrow keys): move, OK, change values, back ---- */
    int act = -1, actDir = +1;
    if (live) {
        const bool up = ImGui::IsKeyPressed(ImGuiKey_GamepadDpadUp, true) ||
                        ImGui::IsKeyPressed(ImGuiKey_GamepadLStickUp, true) ||
                        ImGui::IsKeyPressed(ImGuiKey_UpArrow, true);
        const bool down = ImGui::IsKeyPressed(ImGuiKey_GamepadDpadDown, true) ||
                          ImGui::IsKeyPressed(ImGuiKey_GamepadLStickDown, true) ||
                          ImGui::IsKeyPressed(ImGuiKey_DownArrow, true);
        if (up) { int k = sel - 1; while (k >= 0 && items[k].info) --k; if (k >= 0) sel = k; }
        if (down) { int k = sel + 1; while (k < n && items[k].info) ++k; if (k < n) sel = k; }
        if (up || down) {
            /* keep the selection on screen */
            if (sel < first) first = sel;
            for (;;) {
                float h = 0.0f;
                for (int i = first; i <= sel; ++i) h += tdx_item_h(&items[i], v);
                if (h <= area || first >= sel) break;
                ++first;
            }
            /* on the first selectable row, show any info rows above it too */
            bool onlyInfoAbove = true;
            for (int i = 0; i < sel; ++i) if (!items[i].info) onlyInfoAbove = false;
            if (onlyInfoAbove && up) first = 0;
        }
        if (ImGui::IsKeyPressed(ImGuiKey_GamepadFaceDown, false) ||
            ImGui::IsKeyPressed(ImGuiKey_Enter, false)) act = sel;
        const bool valueRow = page == TDX_PAGE_MAIN && sel <= 5;
        if (valueRow && (ImGui::IsKeyPressed(ImGuiKey_GamepadDpadRight, true) ||
                         ImGui::IsKeyPressed(ImGuiKey_RightArrow, true))) { act = sel; actDir = +1; }
        if (valueRow && (ImGui::IsKeyPressed(ImGuiKey_GamepadDpadLeft, true) ||
                         ImGui::IsKeyPressed(ImGuiKey_LeftArrow, true))) { act = sel; actDir = -1; }
    }
    if (first > maxFirst) first = maxFirst;
    if (first < 0) first = 0;

    /* ---- scroll bar strip (tap or drag) ---- */
    const float stripW = 1.0f * v;
    float before = 0.0f;
    for (int i = 0; i < first; ++i) before += tdx_item_h(&items[i], v);
    if (scrolls) {
        ImGui::SetCursorScreenPos(ImVec2(display.x - stripW, areaTop));
        ImGui::InvisibleButton("##tdxscroll", ImVec2(stripW, area));
        if (ImGui::IsItemActive()) {
            const float thH = area * area / total;
            float want = (io.MousePos.y - thH * 0.5f - areaTop) / area * total;
            int f = 0;
            float acc = 0.0f;
            while (f < maxFirst && acc + tdx_item_h(&items[f], v) * 0.5f < want) {
                acc += tdx_item_h(&items[f], v);
                ++f;
            }
            first = f;
        }
    }

    /* ---- rows ---- */
    float y = areaTop;
    for (int i = first, slot = 0; i < n; ++i, ++slot) {
        const float h = tdx_item_h(&items[i], v);
        if (y + h > areaBot + 0.5f) break;
        const float cy = y + h * 0.5f;
        if (items[i].info) {
            const float ts = 0.30f * v;
            dl->AddText(ImGui::GetFont(), ts, ImVec2(0.6f * v, cy - ts * 0.5f), colGrey, items[i].label);
            y += h;
            continue;
        }
        /* Only the label (the pill area) takes a tap. Ids are per screen slot,
         * so a drag that scrolls the list keeps its item. */
        const float tw = ImGui::CalcTextSize(items[i].label).x;
        ImGui::SetCursorScreenPos(ImVec2(0.3f * v, y));
        char id[24];
        snprintf(id, sizeof id, "##tdxslot%d", slot);
        const bool clicked = ImGui::InvisibleButton(id, ImVec2(tw + 0.6f * v, h));
        if (ImGui::IsItemActivated()) { s_down_item = i; s_down_first = first; s_dragged = false; }
        if (ImGui::IsItemActive() && scrolls && ImGui::IsMouseDragging(0, 0.15f * v)) {
            s_dragged = true;    /* a drag scrolls the list and is never a tap */
            const float dy = ImGui::GetMouseDragDelta(0, 0.0f).y;
            int nf = s_down_first - (int)(dy / pitch + (dy > 0 ? 0.5f : -0.5f));
            first = nf < 0 ? 0 : (nf > maxFirst ? maxFirst : nf);
        }
        if (clicked && !s_dragged && s_down_item >= 0 && !g_binding_capture_active && g_tdx_sc_cap < 0) {
            sel = s_down_item;
            act = s_down_item;
            actDir = +1;
        }
        const bool held = ImGui::IsItemActive() && !s_dragged;
        const bool isSel = held || i == sel;   /* the last row you used stays highlighted */
        if (isSel) {
            dl->AddRectFilled(ImVec2(0.3f * v, cy - pillH * 0.5f),
                              ImVec2(0.3f * v + tw + 0.6f * v, cy + pillH * 0.5f),
                              held ? colHeld : colSel, pillH * 0.5f);
        }
        dl->AddText(ImVec2(0.6f * v, cy - fh * 0.5f), isSel ? colWhite : colGrey, items[i].label);
        y += h;
    }

    /* ---- empty space beside the labels: drag scrolls, a tap does nothing.
     * Submitted after the rows: where two areas overlap, ImGui gives the
     * touch to the one submitted first, so the labels must come first. ---- */
    const float listW = display.x - (scrolls ? stripW : 0.0f);
    ImGui::SetCursorScreenPos(ImVec2(0.0f, areaTop));
    ImGui::InvisibleButton("##tdxlistbg", ImVec2(listW, area));
    if (ImGui::IsItemActivated()) { s_down_item = -1; s_down_first = first; s_dragged = false; }
    if (ImGui::IsItemActive() && scrolls && ImGui::IsMouseDragging(0, 0.15f * v)) {
        s_dragged = true;
        const float dy = ImGui::GetMouseDragDelta(0, 0.0f).y;
        int nf = s_down_first - (int)(dy / pitch + (dy > 0 ? 0.5f : -0.5f));
        first = nf < 0 ? 0 : (nf > maxFirst ? maxFirst : nf);
    }

    /* ---- scroll indicator ---- */
    if (scrolls) {
        const float tx = display.x - 0.32f * v;
        dl->AddRectFilled(ImVec2(tx - 0.06f * v, areaTop), ImVec2(tx + 0.06f * v, areaBot),
                          IM_COL32(255, 255, 255, 55), 0.06f * v);
        before = 0.0f;
        for (int i = 0; i < first; ++i) before += tdx_item_h(&items[i], v);
        const float thH = area * area / total;
        const float thY = areaTop + area * before / total;
        dl->AddRectFilled(ImVec2(tx - 0.10f * v, thY), ImVec2(tx + 0.10f * v, thY + thH),
                          colSel, 0.10f * v);
    }

    /* ---- clear a mapping with X on the Controls page ---- */
    if (live && page == TDX_PAGE_CONTROLS && sel < 8 + TDX_SC_COUNT &&
        ImGui::IsKeyPressed(ImGuiKey_GamepadFaceLeft, false)) {
        if (sel < 8) {
            const GBInputAction a = kTdxControlActions[sel];
            assign_binding_slot(GB_CAPTURE_DEVICE_CONTROLLER, a, 0, make_binding(GB_INPUT_BINDING_NONE, 0));
            assign_binding_slot(GB_CAPTURE_DEVICE_CONTROLLER, a, 1, make_binding(GB_INPUT_BINDING_NONE, 0));
        } else {
            g_tdx_sc_n[sel - 8] = 0;
            save_runtime_preferences();
        }
    }

    /* ---- act on the chosen row ---- */
    if (act >= 0 && act < n && !items[act].info) {
        if (page == TDX_PAGE_MAIN) {
            if (!tdx_change_main(act, actDir)) {
                if (act == 6) g_tdx_menu_page = TDX_PAGE_CONTROLS;
                else if (act == 7) g_tdx_menu_page = TDX_PAGE_CREDITS;
                else tdx_close_menu();
            }
        } else if (page == TDX_PAGE_CONTROLS) {
            if (act < 8) {
                start_binding_capture(GB_CAPTURE_DEVICE_CONTROLLER, kTdxControlActions[act], 0);
            } else if (act < 8 + TDX_SC_COUNT) {
                /* record a shortcut: hold the buttons together, then let go */
                g_tdx_sc_cap = act - 8;
                g_tdx_cap_n = 0;
            } else if (act == 8 + TDX_SC_COUNT) {
                set_default_input_bindings();
                for (int i = 0; i < TDX_SC_COUNT; ++i) g_tdx_sc_n[i] = 0;
                save_runtime_preferences();
                tdx_pill("Controls reset");
            } else {
                tdx_menu_back();
            }
        } else {
            tdx_menu_back();
        }
    }

    /* B goes back (or closes) on a gamepad. */
    if (live && ImGui::IsKeyPressed(ImGuiKey_GamepadFaceRight, false)) tdx_menu_back();

    if (s_pad) {
        if (page == TDX_PAGE_CONTROLS) {
            static const char* const L[3] = {"B", "A", "X"};
            static const char* const T[3] = {"BACK", "REMAP", "CLEAR"};
            tdx_draw_hints(L, T, 3, v, display);
        } else {
            static const char* const L[2] = {"B", "A"};
            static const char* const T[2] = {"BACK", "OK"};
            tdx_draw_hints(L, T, 2, v, display);
        }
    }

    ImGui::End();
    ImGui::PopStyleColor(2);
    ImGui::PopStyleVar(5);
}

/* Runs while the menu is open: the game is paused, only the menu is drawn
 * and handled. Returns false if the app should quit. */
static bool tdx_menu_loop(GBContext* ctx) {
    if (g_audio_device) SDL_PauseAudioDevice(g_audio_device, 1);
    bool keep_running = true;
    while (g_show_menu && keep_running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            ImGui_ImplSDL2_ProcessEvent(&e);
            if (g_tdx_sc_cap >= 0 && e.type == SDL_KEYDOWN && e.key.repeat == 0 &&
                (e.key.keysym.scancode == SDL_SCANCODE_AC_BACK ||
                 e.key.keysym.scancode == SDL_SCANCODE_ESCAPE)) {
                g_tdx_sc_cap = -1;
                g_tdx_cap_n = 0;
                continue;
            }
            if (tdx_shortcut_event(&e, false)) continue;
            /* Android Back cancels a remap in progress (the capture code
             * would otherwise swallow it). */
            if (g_binding_capture_active && e.type == SDL_KEYDOWN && e.key.repeat == 0 &&
                (e.key.keysym.scancode == SDL_SCANCODE_AC_BACK ||
                 e.key.keysym.scancode == SDL_SCANCODE_ESCAPE)) {
                cancel_binding_capture();
                continue;
            }
            if (g_binding_capture_active && handle_binding_capture_event(&e)) {
                /* the mapped button shows on its row; no pop-up */
                continue;
            }
            switch (e.type) {
                case SDL_QUIT:
                    keep_running = false;
                    break;
                case SDL_APP_TERMINATING:
                case SDL_APP_WILLENTERBACKGROUND:
                case SDL_APP_DIDENTERFOREGROUND:
                case SDL_WINDOWEVENT:
                case SDL_RENDER_TARGETS_RESET:
                case SDL_RENDER_DEVICE_RESET:
                case SDL_CONTROLLERDEVICEADDED:
                case SDL_CONTROLLERDEVICEREMOVED:
                    if (!handle_runtime_event(&e, ctx)) keep_running = false;
                    break;
                case SDL_KEYDOWN:
                    if (e.key.repeat == 0 &&
                        (e.key.keysym.scancode == SDL_SCANCODE_ESCAPE ||
                         e.key.keysym.scancode == SDL_SCANCODE_AC_BACK)) {
                        tdx_menu_back();
                    }
                    break;
                case SDL_CONTROLLERBUTTONDOWN:
                    if (e.cbutton.button == SDL_CONTROLLER_BUTTON_GUIDE) tdx_close_menu();
                    break;
                default:
                    break;
            }
        }
        if (!keep_running) break;
        if (g_app_suspended) {
            SDL_Delay(50);
            continue;
        }
        if (g_last_guest_framebuffer_valid) {
            render_frame_internal(g_last_guest_framebuffer, false);
        }
        SDL_Delay(16);
    }
    /* Release everything that was held, so the game never sees a stuck button. */
    memset(g_keyboard_binding_pressed, 0, sizeof(g_keyboard_binding_pressed));
    memset(g_controller_button_binding_pressed, 0, sizeof(g_controller_button_binding_pressed));
    memset(g_controller_axis_binding_pressed, 0, sizeof(g_controller_axis_binding_pressed));
    update_effective_joypad_state();
    if (g_audio_device) SDL_PauseAudioDevice(g_audio_device, 0);
    return keep_running;
}

/* ---- end Tetroid DX menu ---- */
