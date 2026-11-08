; Super Rotation System for Tetris DX, switchable at runtime (Tetroid DX).
;
; Written for Tetroid DX from the SRS description on tetris.wiki
; (https://tetris.wiki/Super_Rotation_System).
;
; What the original game does (bank 1):
;   $AF80 piece X, $AF81 piece Y (down is +), $AF82 rotation 0-3 (+1 = clockwise),
;   $AF85 piece type (0 I, 1 T, 2 Z, 3 S, 4 J, 5 L, 6 O).
;   $5932 collision test: a = 1 if the piece fits.
;   $57C5 A button: rotate clockwise, try one fixed kick from $63D0.
;   $581E B button: rotate anticlockwise, one kick from $63D8.
;   $5897 spawn: rotation 2, then JLSTZ are set to rotation 0 (flat side up).
;
; The game's own 4x4 shape tables ($6210) already place every piece exactly
; like SRS does, with SRS state 0 = DX rotation 2, R = 3, 2 = 0, L = 1. So with
; the toggle on, only two things change:
;   - spawn: every piece keeps rotation 2 (SRS spawn state, flat side down),
;     moved one row down where needed so it appears in the same rows as before;
;   - rotation: the five SRS kick tests (separate table for I; O never moves).
; With the OPTIONS bit clear, the original code runs unchanged.

def OPTIONS                   equ $7fff
def OPT_SRS                   equ 6       ; bit 6: Super Rotation System

def PIECE_X                   equ $af80
def PIECE_Y                   equ $af81
def PIECE_ROT                 equ $af82
def PIECE_TYPE                equ $af85
def SAVE_X                    equ $ac50   ; scratch the original rotation uses too
def SAVE_Y                    equ $ac51
def FUN_LOCK_RESET            equ $565a   ; called by the game after a rotation
def RET_ROTATED               equ $5888   ; plays the rotate sound, returns
def RET_DONE                  equ $5896   ; ret

; ---------------------------------------------------------------- bank 1 hooks
SECTION "srs_cw_site", ROMX[$57c5], BANK[1]
        jp      srs_cw          ; replaces ld a,[PIECE_ROT]

SECTION "srs_ccw_site", ROMX[$581e], BANK[1]
        jp      srs_ccw         ; replaces ld a,[PIECE_ROT]

SECTION "srs_spawn_site", ROMX[$591e], BANK[1]
        jp      srs_spawn       ; replaces ld a,$00 / ld [PIECE_Y],a
        nop
        nop

SECTION "srs_bank1", ROMX[$7ee0], BANK[1]
srs_cw::
        ld      a, [OPTIONS]
        bit     OPT_SRS, a
        jr      nz, .on
        ld      a, [PIECE_ROT]
        jp      $57c8
.on
        ld      e, 1
        jr      srs_common

srs_ccw::
        ld      a, [OPTIONS]
        bit     OPT_SRS, a
        jr      nz, .on
        ld      a, [PIECE_ROT]
        jp      $5821
.on
        ld      e, $ff
srs_common:
        call    srs_rotate
        and     a
        jp      z, RET_DONE
        call    FUN_LOCK_RESET
        jp      RET_ROTATED

srs_spawn::
        ld      a, [OPTIONS]
        bit     OPT_SRS, a
        jr      nz, .on
        ld      a, $00          ; original code
        ld      [PIECE_Y], a
        jp      $5923
.on
        ; Rotation stays 2 (set at $58A5). Y is 1 from $58A3: keep it for
        ; T, J, L, S, Z (rows 1-2, as before) and use 0 for I and O.
        ld      a, [PIECE_TYPE]
        and     a
        jr      z, .top
        cp      6
        jr      nz, .test
.top
        xor     a
        ld      [PIECE_Y], a
.test
        jp      $5932           ; same tail as the original: test the spawn

; ------------------------------------------------------- bank 0 (always mapped)
SECTION "srs_bank0", ROM0[$3d00]
; e = 1 clockwise, $FF anticlockwise. Returns a = 1 rotated, 0 blocked.
srs_rotate::
        ld      a, [PIECE_ROT]
        ld      d, a            ; d = old DX rotation
        add     e
        and     3
        ld      [PIECE_ROT], a
        ld      a, [PIECE_X]
        ld      [SAVE_X], a
        ld      a, [PIECE_Y]
        ld      [SAVE_Y], a
        ; table row = (SRS state * 2 + anticlockwise) * 10 bytes
        ld      a, d
        add     2
        and     3
        add     a
        bit     7, e
        jr      z, .cw
        inc     a
.cw
        ld      c, a
        add     a
        add     a
        add     c               ; * 5
        add     a               ; * 10
        ld      c, a
        ld      b, 0
        ld      hl, srs_kicks_jlstz
        ld      a, [PIECE_TYPE]
        and     a
        jr      nz, .table
        ld      hl, srs_kicks_i
.table
        add     hl, bc
        ld      b, 5
.loop
        ld      a, [hl+]
        ld      c, a
        ld      a, [SAVE_X]
        add     c
        ld      [PIECE_X], a
        ld      a, [hl+]
        ld      c, a
        ld      a, [SAVE_Y]
        add     c
        ld      [PIECE_Y], a
        cp      $80             ; above the top of the board: skip
        jr      nc, .next
        ld      a, [PIECE_X]
        cp      $10             ; left of column 0: skip
        jr      nc, .next
        push    hl
        push    bc
        push    de
        call    srs_fits
        pop     de
        pop     bc
        pop     hl
        and     a
        ret     nz              ; a = 1: rotated
.next
        dec     b
        jr      nz, .loop
        ld      a, [SAVE_X]     ; blocked: put everything back
        ld      [PIECE_X], a
        ld      a, [SAVE_Y]
        ld      [PIECE_Y], a
        ld      a, d
        ld      [PIECE_ROT], a
        xor     a
        ret

; Copy of the game's collision test at bank 1 $5932, so bank-0 code can use
; it without a cross-bank call. a = 1 if the piece fits.
srs_fits::
        ld      hl, $ad00
        ld      a, [PIECE_X]
        ld      c, a
        ld      a, [PIECE_Y]
        and     $0f
        swap    a
        or      c
        ld      c, a
        ld      a, [PIECE_Y]
        and     $f0
        swap    a
        ld      b, a
        add     hl, bc
        ld      c, l
        ld      b, h
        ld      hl, $6202       ; shape pointers (bank 1, mapped in game)
        ld      a, [PIECE_TYPE]
        add     a
        ld      e, a
        ld      d, 0
        add     hl, de
        ld      a, [hl+]
        ld      e, a
        ld      a, [hl]
        ld      d, a
        ld      l, e
        ld      h, d
        ld      de, $0010
        ld      a, [PIECE_ROT]
.rot
        and     a
        jr      z, .shape
        add     hl, de
        dec     a
        jr      .rot
.shape
        ld      d, 4
        ld      e, 4
.cell
        ld      a, [hl+]
        and     a
        jr      nz, .filled
.advance
        inc     bc
        dec     e
        jr      nz, .cell
        dec     d
        jr      z, .fits
        ld      a, $0c
        add     c
        ld      c, a
        ld      a, b
        adc     0
        ld      b, a
        ld      e, 4
        jr      .cell
.filled
        ld      a, [bc]
        cp      $fb             ; empty board cell
        jr      z, .advance
        xor     a
        ret
.fits
        ld      a, 1
        ret

; SRS kick tests from tetris.wiki, converted to DX coordinates (y down, so
; the wiki's y is negated). Rows: from state 0, R, 2, L; clockwise then
; anticlockwise; five (x, y) tests each.
srs_kicks_jlstz:
        db  0, 0,  -1, 0,  -1,-1,   0, 2,  -1, 2   ; 0->R
        db  0, 0,   1, 0,   1,-1,   0, 2,   1, 2   ; 0->L
        db  0, 0,   1, 0,   1, 1,   0,-2,   1,-2   ; R->2
        db  0, 0,   1, 0,   1, 1,   0,-2,   1,-2   ; R->0
        db  0, 0,   1, 0,   1,-1,   0, 2,   1, 2   ; 2->L
        db  0, 0,  -1, 0,  -1,-1,   0, 2,  -1, 2   ; 2->R
        db  0, 0,  -1, 0,  -1, 1,   0,-2,  -1,-2   ; L->0
        db  0, 0,  -1, 0,  -1, 1,   0,-2,  -1,-2   ; L->2
srs_kicks_i:
        db  0, 0,  -2, 0,   1, 0,  -2, 1,   1,-2   ; 0->R
        db  0, 0,  -1, 0,   2, 0,  -1,-2,   2, 1   ; 0->L
        db  0, 0,  -1, 0,   2, 0,  -1,-2,   2, 1   ; R->2
        db  0, 0,   2, 0,  -1, 0,   2,-1,  -1, 2   ; R->0
        db  0, 0,   2, 0,  -1, 0,   2,-1,  -1, 2   ; 2->L
        db  0, 0,   1, 0,  -2, 0,   1, 2,  -2,-1   ; 2->R
        db  0, 0,   1, 0,  -2, 0,   1, 2,  -2,-1   ; L->0
        db  0, 0,  -2, 0,   1, 0,  -2, 1,   1,-2   ; L->2

; ------------------------------------------------------------ next-piece preview
; The preview (bank 1 $4B67, run at every spawn) copies 8 tile numbers from
; $5F68 and 8 attributes from $5FA0 per piece: 4 for the top row, 4 for the
; bottom row. With SRS on, these copies show the SRS spawn orientation (only
; T, J and L differ). Tile $FF with attribute 6 is an empty cell.
SECTION "srs_preview_tiles_site", ROMX[$4b75], BANK[1]
        call    srs_preview_tiles       ; replaces ld hl,$5F68

SECTION "srs_preview_attrs_site", ROMX[$4b84], BANK[1]
        call    srs_preview_attrs       ; replaces ld hl,$5FA0

SECTION "srs_preview", ROM0[$3c00]
srs_preview_tiles:
        ld      hl, $5f68
        ld      a, [OPTIONS]
        bit     OPT_SRS, a
        ret     z
        ld      hl, srs_preview_tile_table
        ret

srs_preview_attrs:
        ld      hl, $5fa0
        ld      a, [OPTIONS]
        bit     OPT_SRS, a
        ret     z
        ld      hl, srs_preview_attr_table
        ret

srs_preview_tile_table:
        db $ff,$ff,$ff,$ff, $b0,$b1,$b1,$b2     ; I (unchanged)
        db $ff,$98,$ff,$ff, $98,$98,$98,$ff     ; T pointing up
        db $a0,$a0,$ff,$ff, $ff,$a0,$a0,$ff     ; Z (unchanged)
        db $ff,$a8,$a8,$ff, $a8,$a8,$ff,$ff     ; S (unchanged)
        db $90,$ff,$ff,$ff, $90,$90,$90,$ff     ; J
        db $ff,$ff,$88,$ff, $88,$88,$88,$ff     ; L
        db $ff,$80,$80,$ff, $ff,$80,$80,$ff     ; O (unchanged)
srs_preview_attr_table:
        db $06,$06,$06,$06, $03,$03,$03,$03     ; I
        db $06,$01,$06,$06, $01,$01,$01,$06     ; T
        db $02,$02,$06,$06, $06,$02,$02,$06     ; Z
        db $06,$02,$02,$06, $02,$02,$06,$06     ; S
        db $01,$06,$06,$06, $01,$01,$01,$06     ; J
        db $06,$06,$00,$06, $00,$00,$00,$06     ; L
        db $06,$00,$00,$06, $06,$00,$00,$06     ; O
