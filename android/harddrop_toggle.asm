; Tetris DX hard drop and ghost piece, switchable at runtime.
;
; Based on harddrop.asm from https://github.com/lightbulb-sun/tetrisdx-harddrop
; by lightbulb-sun, made following a romhacking.net request by SpongeDan.
; Ghost piece idea by GitHub user szechp.
;
; Change for the Android build: instead of separate patched ROMs, every hook
; reads the OPTIONS byte at the end of ROM bank 1, which the app sets from
; its menu. With OPTIONS = 0 the game behaves like the original.
;
; ---------------------------------------------------------------------------
; MIT License
;
; Copyright (c) 2023 lightbulb-sun
;
; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:
;
; The above copyright notice and this permission notice shall be included in all
; copies or substantial portions of the Software.
;
; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
; SOFTWARE.
; ---------------------------------------------------------------------------

def FREE_SPACE                equ $7f80
def OPTIONS                   equ $7fff   ; set by the app, 0 = original game
def OPT_HARDDROP              equ 0       ; bit 0: Up = hard drop
def OPT_GHOST                 equ 1       ; bit 1: ghost piece on
def OPT_GHOST_DARK            equ 2       ; bit 2: dark ghost instead of light

def TETROMINO_HEIGHT          equ $af81
def TETROMINO_SPRITE_Y        equ $ff9d
def GHOST_PALETTE             equ 7
def GHOST_TILE                equ $0d
def BIT_BUTTON_UP             equ 6
def BIT_BUTTON_DOWN           equ 7
def FUN_DRAW_TETROMINO        equ $05b5
def FUN_COLLISION_DETECTION   equ $5932

SECTION "tetromino", ROMX[$421e], BANK[1]
        call    draw_ghost_tetromino

SECTION "down_press", ROMX[$5780], BANK[1]
        jp      check_for_up_press
        nop

SECTION "options", ROMX[OPTIONS], BANK[1]
        db      0

SECTION "code", ROMX[FREE_SPACE], BANK[1]
check_for_up_press::
        push    af
        ld      a, [OPTIONS]
        bit     OPT_HARDDROP, a
        jr      z, .harddrop_off
        pop     af
        bit     BIT_BUTTON_UP, a
        jr      z, .check_for_down_press
        call    initiate_harddrop
        jp      $578e
.harddrop_off
        pop     af
.check_for_down_press
        bit     BIT_BUTTON_DOWN, a
        jp      z, $57c1
        jp      $5784

initiate_harddrop::
.loop
        ld      hl, TETROMINO_HEIGHT
        inc     [hl]
        call    FUN_COLLISION_DETECTION
        and     a
        jr      nz, .loop
        ret

draw_ghost_tetromino::
        push    de
        ; replace original instruction
        call    FUN_DRAW_TETROMINO
        push    af
        ld      a, [OPTIONS]
        bit     OPT_GHOST, a
        jr      nz, .ghost_on
        ; Ghost off: same result as the original single call.
        pop     af
        add     sp, 2           ; drop the saved de
        ret
.ghost_on
        pop     af
.determine_ghost_tetromino_height
        ld      a, [TETROMINO_HEIGHT]
        push    af
        call    initiate_harddrop
        ld      a, [TETROMINO_HEIGHT]
        ld      h, a
        pop     af
        ld      [TETROMINO_HEIGHT], a
        ld      b, a
        ld      a, h
.add_distance_to_active_tetromino_sprite
        sub     b
        dec     a
        sla     a
        sla     a
        sla     a
        dec     a
        ld      b, a
        pop     de
        ldh     a, [TETROMINO_SPRITE_Y]
        add     b
        ldh     [TETROMINO_SPRITE_Y], a
        call    FUN_DRAW_TETROMINO
.set_palette
        ld      a, GHOST_PALETTE
        ld      [$c013+$00], a
        ld      [$c013+$04], a
        ld      [$c013+$08], a
        ld      [$c013+$0c], a
.set_ghost_tile
        ld      a, [OPTIONS]
        bit     OPT_GHOST_DARK, a
        ret     nz
        ld      a, GHOST_TILE
        ld      [$c012+$00], a
        ld      [$c012+$04], a
        ld      [$c012+$08], a
        ld      [$c012+$0c], a
        ret
