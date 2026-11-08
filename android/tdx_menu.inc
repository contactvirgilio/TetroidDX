/* ---- Tetroid DX menu (inserted by android/patch_project.py) ----
 * The menu is the Java PATCHES card drawn by the touch overlay
 * (TouchControls.java). While it is open the game is paused (tdx_menu_loop)
 * and held buttons are released on close. ImGui draws nothing of its own. */

static void render_frame_internal(const uint32_t* framebuffer, bool count_guest_frame);
static bool handle_runtime_event(const SDL_Event* event, GBContext* ctx);

static void tdx_draw_menu(void) {}

static void tdx_close_menu(void) {
    g_show_menu = false;
    if (g_binding_capture_active) cancel_binding_capture();
}

/* ---- PATCHES card and quick buttons (Nov 2026) ----
 * The main menu is now the Java PATCHES card drawn by the touch overlay (the
 * same style as the DKC STATES card). Java reads and changes the options
 * through nativeGet/nativeSet and asks for the menu to open or close through
 * nativeRequest; the game thread applies those requests at
 * the start of each frame. */
static std::atomic<int> g_tdx_req{0};
enum { TDX_REQ_OPEN = 1, TDX_REQ_CLOSE = 2, TDX_REQ_SAVE = 16 };   /* 32: Hold (see nativeRequest) */

static void tdx_process_requests(void) {
    const int r = g_tdx_req.exchange(0);
    if (!r) return;
    if (r & TDX_REQ_SAVE) save_runtime_preferences();
    if (r & TDX_REQ_CLOSE) tdx_close_menu();
    if (r & TDX_REQ_OPEN) g_show_menu = true;
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

#if defined(__ANDROID__)
/* Java side of the options. ids: 0 Hard Drop, 1 Ghost, 2 Don't Panic,
 * 3 Skip Celebrations, 4 Palette, 5 Scanlines, 6 Mute, 7 menu page (-1 closed),
 * 8 Korobeiniki, 9 SRS, 10 Randomizer, 11 Hold; read only: 12 held piece
 * (-1 none), 13 queue length, 14-18 queue, 19 in play, 20-26 piece colours,
 * 27 SRS on, 28 hold used this piece; 29 preview layout (0 off, 1-4);
 * 30 Lock Delay; 31 the game's next piece ($AF84). */
extern "C" JNIEXPORT jint JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeGet(JNIEnv* env, jclass cls, jint id) {
    (void)env; (void)cls;
    switch (id) {
        case 0: return g_tdx_harddrop ? 1 : 0;
        case 1: return g_tdx_ghost;
        case 2: return g_tdx_dontpanic ? 1 : 0;
        case 3: return g_tdx_skipceleb;
        case 4: return g_tdx_palette;
        case 5: return g_tdx_scanlines ? 1 : 0;
        case 6: return g_audio_muted.load(std::memory_order_relaxed) ? 1 : 0;
        case 7: return g_show_menu ? 0 : -1;
        case 8: return g_tdx_korobeiniki;
        case 9: return g_tdx_srs ? 1 : 0;
        case 10: return g_tdx_randomizer;
        case 11: return g_tdx_hold ? 1 : 0;
        case 12: return g_tdx_hold_piece;
        case 13: return g_tdx_randomizer != 0 ? g_tdx_qn : 0;
        case 14: case 15: case 16: case 17: case 18:
            return (g_tdx_randomizer != 0 && id - 14 < g_tdx_qn) ? g_tdx_q[id - 14] : -1;
        case 19: return g_tdx_in_play.load(std::memory_order_relaxed) ? 1 : 0;
        case 20: case 21: case 22: case 23: case 24: case 25: case 26:
            return (jint)tdx_piece_argb(g_registered_ctx, id - 20);
        case 27: return g_tdx_srs ? 1 : 0;
        case 28: return g_tdx_hold_used ? 1 : 0;
        case 29: return g_tdx_preview;
        case 30: return g_tdx_lockdelay ? 1 : 0;
        case 31: return (g_registered_ctx && g_registered_ctx->eram) ? g_registered_ctx->eram[0xF84] : -1;
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
        case 3: g_tdx_skipceleb = (v >= 0 && v <= 2) ? (int)v : 0; break;
        case 4: g_tdx_palette = (v >= 0 && v < TDX_PAL_COUNT && v != TDX_PAL_PASTEL && v != TDX_PAL_SUNSET && v != TDX_PAL_GRAPHITE) ? (int)v : TDX_PAL_GBC_LCD; break;
        case 5: g_tdx_scanlines = v != 0; break;
        case 6: g_audio_muted.store(v != 0, std::memory_order_relaxed); break;
        case 8: g_tdx_korobeiniki = v < 0 ? 0 : (v > 2 ? 2 : v); break;
        case 9: g_tdx_srs = v != 0; break;
        case 10: g_tdx_randomizer = (v >= 0 && v <= 2) ? (int)v : 0; break;
        case 11: g_tdx_hold = v != 0; break;
        case 29: g_tdx_preview = (v >= 0 && v <= 5) ? (int)v : 4; break;
        case 30: g_tdx_lockdelay = v != 0; break;
        default: return;
    }
    g_tdx_req.fetch_or(TDX_REQ_SAVE);
}

/* Colour of one game pixel as the player sees it (palette filters applied); 0 if unknown. */
extern "C" JNIEXPORT jint JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeSample(JNIEnv* env, jclass cls, jint x, jint y) {
    (void)env; (void)cls;
    if (!g_last_guest_framebuffer_valid || x < 0 || y < 0 || x >= GB_SCREEN_WIDTH || y >= GB_SCREEN_HEIGHT) return 0;
    uint32_t in = g_last_guest_framebuffer[(size_t)y * GB_SCREEN_WIDTH + (size_t)x], out = in;
    tdx_copy_row(&out, &in, 1);
    return (jint)(out | 0xFF000000u);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativePieceImage(JNIEnv* env, jclass cls, jint t, jintArray arr) {
    (void)cls;
    if (!arr || env->GetArrayLength(arr) < 32 * 16) return JNI_FALSE;
    static uint32_t img[32 * 16];
    if (!tdx_piece_image(g_registered_ctx, (int)t, img)) return JNI_FALSE;
    env->SetIntArrayRegion(arr, 0, 32 * 16, (const jint*)img);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_tetrisdx_recomp_TouchControls_nativeRequest(JNIEnv* env, jclass cls, jint bits) {
    (void)env; (void)cls;
    if (bits & 32) g_tdx_hold_req.store(true, std::memory_order_relaxed);   /* Hold button */
    g_tdx_req.fetch_or((int)bits & ~32);
}
#endif

/* Runs while the menu is open: the game is paused, only the menu is drawn
 * and handled. Returns false if the app should quit. */
static bool tdx_menu_loop(GBContext* ctx) {
    if (g_audio_device) SDL_PauseAudioDevice(g_audio_device, 1);
    bool keep_running = true;
    while (g_show_menu && keep_running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            ImGui_ImplSDL2_ProcessEvent(&e);
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
                        tdx_close_menu();
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
