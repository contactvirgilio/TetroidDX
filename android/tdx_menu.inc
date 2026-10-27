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
static bool g_tdx_menu_dragged = false;
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
    tdx_pill(msg);
    return true;
}

/* One full-width row: invisible tap target, teal pill when selected. */
static bool tdx_menu_row(int i, float y, const char* label, float v, float fh,
                         float width, bool focus_this, bool* out_focused) {
    const float pitch = 0.86f * v, pillH = 0.8f * v;
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImGui::SetCursorPos(ImVec2(0.0f, y));
    const ImVec2 top = ImGui::GetCursorScreenPos();
    if (focus_this) ImGui::SetKeyboardFocusHere(0);
    char id[24];
    snprintf(id, sizeof id, "##tdxrow%d", i);
    bool pressed = ImGui::InvisibleButton(id, ImVec2(width, pitch));
    const bool focused = ImGui::IsItemFocused();
    const bool held = ImGui::IsItemActive() && !g_tdx_menu_dragged;
    const bool sel = focused || held;
    const float cy = top.y + pitch * 0.5f;
    if (sel) {
        const float tw = ImGui::CalcTextSize(label).x;
        dl->AddRectFilled(ImVec2(top.x + 0.3f * v, cy - pillH * 0.5f),
                          ImVec2(top.x + 0.3f * v + tw + 0.6f * v, cy + pillH * 0.5f),
                          held ? IM_COL32(38, 166, 154, 255) : IM_COL32(0, 137, 123, 255),
                          pillH * 0.5f);
    }
    dl->AddText(ImVec2(top.x + 0.6f * v, cy - fh * 0.5f),
                sel ? IM_COL32(255, 255, 255, 255) : IM_COL32(184, 184, 184, 255), label);
    if (out_focused) *out_focused = focused;
    return pressed && !g_tdx_menu_dragged;
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

static void tdx_draw_menu(void) {
    ImGuiIO& io = ImGui::GetIO();
    const ImVec2 display = io.DisplaySize;
    const float v = (display.x < display.y ? display.x : display.y) / 7.0f;
    io.FontGlobalScale = (0.46f * v) / (g_tdx_menu_font ? TDX_FONT_PX : 13.0f);
    const float pitch = 0.86f * v, rowTop0 = 1.35f * v;

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
                     ImGuiWindowFlags_NoScrollWithMouse);
    const float fh = ImGui::GetFontSize();

    const char* heading = g_tdx_menu_page == TDX_PAGE_CONTROLS ? "Controls"
                        : g_tdx_menu_page == TDX_PAGE_CREDITS  ? "Credits" : "Menu";
    {
        const float tsz = 0.75f * v;
        ImGui::SetWindowFontScale(tsz / fh);
        ImGui::SetCursorPos(ImVec2(0.5f * v, 0.7f * v - tsz * 0.5f));
        ImGui::TextColored(ImVec4(192 / 255.0f, 51 / 255.0f, 107 / 255.0f, 1.0f), "%s", heading);
        ImGui::SetWindowFontScale(1.0f);
    }

    const bool focus_first = g_tdx_menu_focus_first || ImGui::IsWindowAppearing();
    g_tdx_menu_focus_first = false;
    const bool left = ImGui::IsKeyPressed(ImGuiKey_GamepadDpadLeft, true);
    const bool right = ImGui::IsKeyPressed(ImGuiKey_GamepadDpadRight, true);
    int rows = 0;

    /* The rows live in their own scrolling area that ends above the button
     * hints, so a row can never slide under them; the title stays fixed. */
    ImGui::SetCursorPos(ImVec2(0.0f, rowTop0));
    ImGui::BeginChild("##tdxlist", ImVec2(display.x, display.y - 1.15f * v - rowTop0), false,
                      ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse |
                          ImGuiWindowFlags_NoBackground);

    /* Touch: drag to scroll; a drag never counts as a tap. */
    if (ImGui::IsMouseClicked(0)) g_tdx_menu_dragged = false;
    if (ImGui::IsMouseDragging(0, 0.25f * v)) {
        ImGui::SetScrollY(ImGui::GetScrollY() - io.MouseDelta.y);
        g_tdx_menu_dragged = true;
    }


    if (g_tdx_menu_page == TDX_PAGE_MAIN) {
        char labels[9][64];
        snprintf(labels[0], 64, "Hard Drop: %s", g_tdx_harddrop ? "On" : "Off");
        snprintf(labels[1], 64, "Ghost Piece: %s", kTdxGhostNames[g_tdx_ghost % 3]);
        snprintf(labels[2], 64, "Don't Panic: %s", g_tdx_dontpanic ? "On" : "Off");
        snprintf(labels[3], 64, "Skip Celebrations: %s", g_tdx_skipceleb ? "On" : "Off");
        snprintf(labels[4], 64, "Palette: %s", kTdxPaletteNames[g_tdx_palette % TDX_PAL_COUNT]);
        snprintf(labels[5], 64, "Scanlines: %s", g_tdx_scanlines ? "On" : "Off");
        snprintf(labels[6], 64, "Controls");
        snprintf(labels[7], 64, "Credits");
        snprintf(labels[8], 64, "Close");
        rows = 9;
        for (int i = 0; i < rows; ++i) {
            bool focused = false;
            bool pressed = tdx_menu_row(i, i * pitch, labels[i], v, fh, display.x,
                                        focus_first && i == 0, &focused);
            if (focused && left) tdx_change_main(i, -1);
            if (focused && right) pressed = true;
            if (!pressed) continue;
            if (tdx_change_main(i, +1)) continue;
            if (i == 6) { g_tdx_menu_page = TDX_PAGE_CONTROLS; g_tdx_menu_focus_first = true; }
            else if (i == 7) { g_tdx_menu_page = TDX_PAGE_CREDITS; g_tdx_menu_focus_first = true; }
            else if (i == 8) { tdx_close_menu(); }
        }
    } else if (g_tdx_menu_page == TDX_PAGE_CONTROLS) {
        rows = 10;
        for (int i = 0; i < 8; ++i) {
            const GBInputAction act = kTdxControlActions[i];
            char label[96];
            if (g_binding_capture_active && g_binding_capture_action == act) {
                snprintf(label, sizeof label, "%s: press a button...", kTdxControlNames[i]);
            } else {
                /* Show the main assignment only, so rows fit in portrait. */
                std::string b0 = binding_display_label(g_controller_bindings[act][0]);
                const size_t cut = b0.find(" / ");
                if (cut != std::string::npos) b0 = b0.substr(0, cut);
                snprintf(label, sizeof label, "%s: %s", kTdxControlNames[i], b0.c_str());
            }
            bool focused = false;
            const bool pressed = tdx_menu_row(i, i * pitch, label, v, fh, display.x,
                                              focus_first && i == 0, &focused);
            if (pressed && !g_binding_capture_active) {
                start_binding_capture(GB_CAPTURE_DEVICE_CONTROLLER, act, 0);
            }
            if (focused && !g_binding_capture_active &&
                ImGui::IsKeyPressed(ImGuiKey_GamepadFaceLeft, false)) {
                assign_binding_slot(GB_CAPTURE_DEVICE_CONTROLLER, act, 0,
                                    make_binding(GB_INPUT_BINDING_NONE, 0));
                assign_binding_slot(GB_CAPTURE_DEVICE_CONTROLLER, act, 1,
                                    make_binding(GB_INPUT_BINDING_NONE, 0));
                char msg[48];
                snprintf(msg, sizeof msg, "%s cleared", kTdxControlNames[i]);
                tdx_pill(msg);
            }
        }
        if (tdx_menu_row(8, 8 * pitch, "Reset to defaults", v, fh, display.x, false, NULL)) {
            set_default_input_bindings();
            save_runtime_preferences();
            tdx_pill("Controls reset");
        }
        if (tdx_menu_row(9, 9 * pitch, "Back", v, fh, display.x, false, NULL)) {
            tdx_menu_back();
        }
    } else {
        /* Credits: small grey text, then a Back row. */
        static const char* const lines[] = {
            "Hard drop & ghost piece: lightbulb-sun (MIT)",
            "  requested by SpongeDan; ghost idea by szechp",
            "Don't Panic: DevEd",
            "Skip Celebrations: llakssz",
            "Recompiler: GB Recompiled by arcanite24 (MIT)",
            "SDL2 (zlib), Dear ImGui (MIT), RGBDS",
            "Font: BPreplay (SIL Open Font License)",
            "Unofficial fan port. Tetris DX is a",
            "trademark of its respective owners.",
        };
        const int n = (int)(sizeof lines / sizeof lines[0]);
        const float ts = 0.30f * v, lp = 0.44f * v;
        ImDrawList* dl = ImGui::GetWindowDrawList();
        const ImVec2 origin = ImGui::GetWindowPos();
        for (int k = 0; k < n; ++k) {
            dl->AddText(ImGui::GetFont(), ts,
                        ImVec2(origin.x + 0.6f * v, origin.y - ImGui::GetScrollY() + k * lp),
                        IM_COL32(184, 184, 184, 255), lines[k]);
        }
        const float backY = n * lp + 0.2f * v;
        if (tdx_menu_row(0, backY, "Back", v, fh, display.x, focus_first, NULL)) {
            tdx_menu_back();
        }
        rows = (int)(backY / pitch) + 1;
    }

    ImGui::SetCursorPos(ImVec2(0.0f, rows * pitch));
    ImGui::Dummy(ImVec2(1.0f, 0.05f * v));
    ImGui::EndChild();

    /* B goes back (or closes) on a gamepad, unless a button is being remapped. */
    if (!g_binding_capture_active && ImGui::IsKeyPressed(ImGuiKey_GamepadFaceRight, false)) {
        tdx_menu_back();
    }

    if (io.NavVisible) {
        if (g_tdx_menu_page == TDX_PAGE_CONTROLS) {
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
            if (g_binding_capture_active && handle_binding_capture_event(&e)) {
                if (!g_binding_capture_active) tdx_pill("Button mapped");
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
