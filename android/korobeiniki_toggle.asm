; Tetris DX "Music Fix" (classic Korobeiniki), switchable at runtime.
;
; Based on "Tetris DX - Music Fix" by Marc Robledo (2020), which makes Music
; Type A play the classic Korobeiniki song (song $64) and changes the two
; "close to game over" songs to $64 and $4B. Here the danger songs only change
; while Korobeiniki is the song playing, so Music C keeps its own danger songs.
; The music choice follows bit 5 of the OPTIONS byte that the app sets from
; its menu. With the bit clear the game behaves like the original.
;
; The OPTIONS byte is read through $7FFF of the ROM bank mapped at the time:
; bank 1 for the danger songs, bank 5 for the music choice. The app writes
; the same byte to both (file offsets $7FFF and $17FFF).
;
; Original code (checked by the workflow before patching):
;   bank 1 $4C2B: ld a,$5E / ld [$AC53],a / ld a,$5F / ld [$AC54],a / ld a,$4C ...
;   bank 5 $4D00: ld bc,$0037 / jr $4D0D   (Music Type A)
;   bank 5 $4D10: ld [$AC0E],a / jp $3F18

def OPTIONS                   equ $7fff
def OPT_CLASSIC               equ 5       ; bit 5: classic Korobeiniki music
def KORO_PLUS                 equ $3ffd   ; bank 0, set by the app: 1 = Korobeiniki (Plus)

; ---- danger songs (bank 1) -------------------------------------------------
; $4C2C is an entry point of its own (the $5E operand also runs as ld e,[hl]),
; so $4C2B-$4C2F stay untouched; the call replaces ld a,$5F / ld [$AC54],a and
; returns to the nops, which lead on to ld a,$4C at $4C35.
SECTION "koro_danger_site", ROMX[$4c30], BANK[1]
        call    koro_danger
        nop
        nop

; Bank 0 (always mapped), in the free space at its end.
SECTION "koro_danger_code", ROM0[$3fc4]
koro_danger::
        ; This routine runs for Music C and for any song the game does not
        ; know, Korobeiniki included (bank 1 $4BF1 picks the danger songs from
        ; the song playing, $AC0E: $44 Music A, $45 Music B, else this). Only
        ; Korobeiniki ($64) gets Marc's danger songs: it keeps playing at the
        ; first danger stage and song $4B plays at the second. Music C keeps
        ; its own. With the toggle off, $64 is never playing, so this is the
        ; original code.
        push    af              ; keep the caller's flags exactly as before
        ld      a, [$ac0e]
        cp      $64
        jr      z, .korobeiniki
        pop     af
        ld      a, $5f          ; original second danger song
        ld      [$ac54], a
        ret
.korobeiniki
        ld      a, [KORO_PLUS]  ; Original: no first danger song (keeps $64);
        and     a               ; Plus: song $66, "Last Stand" (below)
        ld      a, $64
        jr      z, .first
        ld      a, $66
.first
        ld      [$ac53], a
        pop     af
        ld      a, $4b
        ld      [$ac54], a
        ret

; ---- Music Type A (bank 5) -------------------------------------------------
SECTION "koro_music_site", ROMX[$4d00], BANK[5]
        jp      koro_music      ; replaces ld bc,$0037 (the jr at $4D03 stays)

; Free space at the end of bank 5 ($7FEB-$7FFE), so every jump stays in bank 5.
SECTION "koro_music_code", ROMX[$7feb], BANK[5]
koro_music::
        ld      a, [OPTIONS]
        bit     OPT_CLASSIC, a
        ld      bc, $0037
        jp      z, $4d03        ; original: jr $4D0D (a = c + $0D)
        ld      bc, $0064       ; Marc's values: song $64
        ld      a, $64
        jp      $4d10

SECTION "koro_options_bank5", ROMX[OPTIONS], BANK[5]
        db      0               ; bank-5 copy of the options byte (set by the app)

; ---- Korobeiniki (Plus): "Last Stand", our own first danger song --------------
; Song $66 is an unused slot of the song table (bank 12 $546D, 6 bytes per
; song). It points at the song below in the empty bank $12, in the engine's own
; song format (header, four channel streams that loop). Nothing else uses $66.
SECTION "koro_song66", ROMX[$56d1], BANK[12]
        db      $00, $40, $12, $32, $00, $04

SECTION "koro_last_stand", ROMX[$4000], BANK[$12]
        db      $04, $02, $1b, $40, $e1, $40, $ad, $41, $79, $42, $1d, $40, $e3, $40, $af, $41
        db      $7b, $42, $1a, $40, $e0, $40, $ac, $41, $78, $42, $b1, $bd, $00, $bc, $5c, $be
        db      $05, $c0, $40, $bf, $4e, $d8, $4a, $14, $9c, $d8, $4d, $14, $8c, $d8, $51, $14
        db      $98, $d8, $4d, $14, $8c, $d8, $4a, $14, $8c, $d8, $51, $14, $98, $d8, $4f, $14
        db      $8c, $d8, $4d, $14, $8c, $d8, $4c, $14, $98, $d8, $4a, $14, $8c, $d8, $4c, $14
        db      $8c, $d8, $4d, $14, $9c, $d8, $4a, $14, $8c, $d8, $46, $14, $98, $d8, $4a, $14
        db      $8c, $d8, $4d, $14, $8c, $d8, $4c, $14, $98, $d8, $49, $14, $8c, $d8, $4c, $14
        db      $8c, $d8, $51, $14, $a0, $d8, $51, $14, $8c, $d8, $51, $14, $8c, $d8, $4f, $14
        db      $8c, $d8, $4d, $14, $8c, $d8, $4c, $14, $8c, $d8, $4d, $14, $8c, $d8, $4a, $14
        db      $98, $d8, $4f, $14, $9c, $d8, $4d, $14, $8c, $d8, $4a, $14, $98, $d8, $46, $14
        db      $8c, $d8, $4a, $14, $8c, $d8, $4c, $14, $8c, $d8, $4c, $14, $8c, $d8, $49, $14
        db      $8c, $d8, $4c, $14, $8c, $d8, $51, $14, $98, $d8, $4f, $14, $8c, $d8, $4c, $14
        db      $8c, $d8, $45, $14, $8c, $d8, $49, $14, $8c, $d8, $4c, $14, $8c, $d8, $51, $14
        db      $8c, $d8, $4f, $14, $8c, $d8, $4c, $14, $8c, $d8, $49, $14, $98, $b2, $1d, $40
        db      $b1, $bd, $00, $be, $1f, $c0, $28, $bf, $45, $d8, $8c, $d8, $45, $0d, $8c, $d8
        db      $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8, $45, $0d
        db      $8c, $d8, $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8
        db      $45, $0d, $8c, $d8, $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8, $41, $0d, $8c, $d8
        db      $8c, $d8, $41, $0d, $8c, $d8, $8c, $d8, $41, $0d, $8c, $d8, $8c, $d8, $41, $0d
        db      $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8
        db      $40, $0d, $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8, $45, $0d, $8c, $d8
        db      $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8, $45, $0d, $8c, $d8, $8c, $d8, $45, $0d
        db      $8c, $d8, $8c, $d8, $3e, $0d, $8c, $d8, $8c, $d8, $3e, $0d, $8c, $d8, $8c, $d8
        db      $3e, $0d, $8c, $d8, $8c, $d8, $3e, $0d, $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8
        db      $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8, $40, $0d
        db      $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8, $40, $0d, $8c, $d8, $8c, $d8
        db      $40, $0d, $8c, $d8, $8c, $d8, $40, $0d, $8c, $b2, $e3, $40, $b1, $bd, $00, $be
        db      $2a, $c0, $50, $bf, $64, $db, $26, $1c, $98, $db, $26, $1c, $8c, $db, $32, $1c
        db      $8c, $db, $26, $1c, $98, $db, $26, $1c, $8c, $db, $32, $1c, $8c, $db, $26, $1c
        db      $98, $db, $26, $1c, $8c, $db, $32, $1c, $8c, $db, $26, $1c, $98, $db, $26, $1c
        db      $8c, $db, $32, $1c, $8c, $db, $2e, $1c, $98, $db, $2e, $1c, $8c, $db, $3a, $1c
        db      $8c, $db, $2e, $1c, $98, $db, $2e, $1c, $8c, $db, $3a, $1c, $8c, $db, $2d, $1c
        db      $98, $db, $2d, $1c, $8c, $db, $39, $1c, $8c, $db, $2d, $1c, $98, $db, $2d, $1c
        db      $8c, $db, $39, $1c, $8c, $db, $26, $1c, $98, $db, $26, $1c, $8c, $db, $32, $1c
        db      $8c, $db, $26, $1c, $98, $db, $26, $1c, $8c, $db, $32, $1c, $8c, $db, $2b, $1c
        db      $98, $db, $2b, $1c, $8c, $db, $37, $1c, $8c, $db, $2b, $1c, $98, $db, $2b, $1c
        db      $8c, $db, $37, $1c, $8c, $db, $2d, $1c, $98, $db, $2d, $1c, $8c, $db, $39, $1c
        db      $8c, $db, $2d, $1c, $98, $db, $2d, $1c, $8c, $db, $39, $1c, $8c, $db, $2d, $1c
        db      $98, $db, $2d, $1c, $8c, $db, $39, $1c, $8c, $db, $2d, $1c, $98, $db, $2d, $1c
        db      $8c, $db, $39, $1c, $8c, $b2, $af, $41, $b1, $bd, $00, $be, $7f, $c0, $40, $bf
        db      $45, $d3, $2c, $0c, $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c, $db, $40, $98
        db      $d3, $34, $18, $8c, $d3, $34, $18, $8c, $d3, $2c, $0c, $98, $d3, $34, $18, $8c
        db      $d3, $34, $18, $8c, $db, $40, $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c, $d3
        db      $2c, $0c, $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c, $db, $40, $98, $d3, $34
        db      $18, $8c, $d3, $34, $18, $8c, $d3, $2c, $0c, $98, $d3, $34, $18, $8c, $d3, $34
        db      $18, $8c, $db, $40, $8c, $db, $40, $8c, $db, $40, $8c, $d3, $2c, $0c, $8c, $d3
        db      $2c, $0c, $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c, $db, $40, $98, $d3, $34
        db      $18, $8c, $d3, $34, $18, $8c, $d3, $2c, $0c, $98, $d3, $34, $18, $8c, $d3, $34
        db      $18, $8c, $db, $40, $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c, $d3, $2c, $0c
        db      $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c, $db, $40, $98, $d3, $34, $18, $8c
        db      $d3, $34, $18, $8c, $d3, $2c, $0c, $98, $d3, $34, $18, $8c, $d3, $34, $18, $8c
        db      $db, $40, $8c, $db, $40, $8c, $db, $40, $8c, $d3, $2c, $0c, $8c, $b2, $7b, $42
