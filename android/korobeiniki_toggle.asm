; Tetris DX "Music Fix" (classic Korobeiniki), switchable at runtime.
;
; Based on "Tetris DX - Music Fix" by Marc Robledo (2020), which makes Music
; Type A play the classic Korobeiniki song (song $64) and changes the two
; "close to game over" songs to $64 and $4B. Tetroid DX keeps the game's own
; first danger song ($5E) so both danger stages are heard, and uses $4B for
; the second. Small routines pick the original or these songs from bit 5 of
; the OPTIONS byte that the app sets from its menu. With the bit clear the
; game behaves like the original.
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
        push    af              ; keep the caller's flags exactly as before
        ld      a, [OPTIONS]
        bit     OPT_CLASSIC, a
        jr      nz, .classic
        pop     af
        ld      a, $5f          ; original second danger song
        ld      [$ac54], a
        ret
.classic
        ; The first danger song stays the game's own ($5E, already stored at
        ; $4C2D). Marc's patch also set it to $64, the same song as Music
        ; Type A, so the first danger stage never sounded different.
        pop     af
        ld      a, $4b          ; second danger song from Marc's patch
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
