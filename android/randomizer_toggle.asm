; Piece randomizer choice for Tetris DX (Tetroid DX), switchable at runtime.
;
;   0  Tetris DX's own randomizer (the original code runs unchanged)
;   1  7-Bag      } chosen by the app (tdx_platform.inc), which keeps a queue
;   2  35-Bag     } so it can show the upcoming pieces
;
; The mode is read from bank 0 $3FFF (RANDOMIZER) and, for modes 1 and 2, the
; next piece from bank 0 $3FFE; the app writes both. Each piece taken is
; counted in work RAM $CF80 so the app knows when to move its queue on.
; The original game picks the next piece in bank 1 at $58BC-$58E7 and stores
; it at $58E8 (ld [$AF84],a). The hook replaces the first instruction there;
; with mode 0 it runs that instruction and carries on exactly as before.
; The first piece of a game comes from the game's own setup, so the chosen
; bag takes over from the second piece.

def RANDOMIZER                equ $3fff   ; bank 0, set by the app
def QUEUE_PIECE               equ $3ffe   ; bank 0, set by the app
def DX_COUNTER                equ $af83
def RS_TAKEN                  equ $cf80   ; pieces taken (wraps), read by the app

SECTION "rand_site", ROMX[$58bc], BANK[1]
        jp      rand_stub       ; replaces ld a,[DX_COUNTER]

SECTION "rand_bank1", ROMX[$7f30], BANK[1]
rand_stub::
        ld      a, [RANDOMIZER]
        and     a
        jr      nz, .other
        ld      a, [DX_COUNTER] ; original instruction
        jp      $58bf
.other
        call    rand_next
        jp      $58e8           ; ld [$AF84],a and on as normal

SECTION "rand_bank0", ROM0[$3a00]
rand_next::
        ld      hl, RS_TAKEN
        inc     [hl]
        ld      a, [QUEUE_PIECE]
        ret
