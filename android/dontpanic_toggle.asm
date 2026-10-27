; Tetris DX "Don't Panic", switchable at runtime.
;
; Based on "Don't Panic" by DevEd (romhacking.net hack #6879), which replaces
; one instruction at bank 1 $4C68 with NOPs. Here that instruction is moved
; into a small routine that runs it unless the Don't Panic bit is set in the
; OPTIONS byte, which the app sets from its menu. With the bit clear the game
; behaves like the original.
;
; The original instruction bytes are read from the player's ROM at build time
; and written to dontpanic_orig.inc by the workflow (see android.yml).

def OPTIONS                   equ $7fff   ; shared with harddrop_toggle.asm
def OPT_DONTPANIC             equ 3       ; bit 3: Don't Panic on
def SITE                      equ $4c68   ; instruction patched by DevEd
def SITE_END                  equ SITE + 3
def HOOK_ADDR                 equ $7f6c   ; free space just before harddrop code

SECTION "dontpanic_site", ROMX[SITE], BANK[1]
        jp      dontpanic_hook

SECTION "dontpanic_hook", ROMX[HOOK_ADDR], BANK[1]
dontpanic_hook::
        push    af
        ld      a, [OPTIONS]
        bit     OPT_DONTPANIC, a
        jr      nz, .skip
        pop     af
        INCLUDE "dontpanic_orig.inc"    ; the original instruction(s)
        jp      SITE_END
.skip
        pop     af
        jp      SITE_END
