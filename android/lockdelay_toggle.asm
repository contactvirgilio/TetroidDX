; Lock delay for Tetris DX (Tetroid DX), switchable at runtime.
;
; The original game locks a piece the moment it cannot fall any further (on
; the gravity tick, or at once when soft-dropping onto the stack). With bit 7
; of the OPTIONS byte set, a piece that rests on the stack waits LOCK_FRAMES
; frames (half a second) before it locks, and each successful move or
; rotation while it rests starts the wait again, up to MAX_RESETS times per
; row (the count starts over when the piece falls to a new lowest row), as in
; modern Tetris. Soft drop onto the stack no longer locks at once; hard drop
; still does. With the bit clear the original code runs.
;
; Game RAM: $AF81 piece Y, $AF86 gravity counter, $AC57 last row seen.
; Lock delay state in work RAM $CF90-$CF92 (unused by the game).
; Original code (checked by the workflow):
;   bank 1 $5607: ld a,[$AC57] / ld b,a       (start of gravity, every frame)
;   bank 1 $565A: ld hl,$AF81                 (after a move, rotation or fall)
;   bank 1 $578E: ld hl,$AF81 / dec [hl]      (soft or hard drop hit the stack)

def OPTIONS                   equ $7fff   ; bank 1, set by the app
def OPT_LOCK                  equ 7
def PIECE_Y                   equ $af81
def GRAVITY                   equ $af86
def LD_TIMER                  equ $cf90
def LD_COUNT                  equ $cf91
def LD_LOWEST                 equ $cf92
def LOCK_FRAMES               equ 30
def MAX_RESETS                equ 15

SECTION "lock_gravity_site", ROMX[$5607], BANK[1]
        call    lock_tick       ; Z: piece rests, skip gravity this frame
        ret     z

SECTION "lock_reset_site", ROMX[$565a], BANK[1]
        call    lock_reset      ; replaces ld hl,$AF81

SECTION "lock_land_site", ROMX[$578e], BANK[1]
        call    lock_land       ; replaces ld hl,$AF81 / dec [hl]
        ret     nz              ; NZ: soft drop rests on the stack, no lock yet

SECTION "lock_bank0", ROM0[$3800]
; Every frame, before gravity. Returns Z to skip gravity, or NZ with b set as
; the original ld a,[$AC57] / ld b,a would.
lock_tick::
        ld      a, [OPTIONS]
        bit     OPT_LOCK, a
        jr      z, .orig
        ; a new lowest row (or a new piece, which starts higher): count anew
        ld      a, [PIECE_Y]
        ld      hl, LD_LOWEST
        cp      [hl]
        jr      z, .row_known
        ld      [hl], a
        xor     a
        ld      [LD_COUNT], a
.row_known
        ld      hl, PIECE_Y     ; resting on the stack if one row down is blocked
        inc     [hl]
        call    srs_fits
        ld      hl, PIECE_Y
        dec     [hl]
        and     a
        jr      nz, .air
        ld      hl, LD_TIMER
        dec     [hl]
        jr      z, .expired
        xor     a               ; Z: wait
        ret
.expired
        ld      a, 1            ; let this frame's gravity step run: it locks
        ld      [GRAVITY], a
.air
        ld      a, LOCK_FRAMES
        ld      [LD_TIMER], a
.orig
        ld      a, [$ac57]
        ld      b, a
        or      1               ; NZ: run gravity
        ret

; After a successful move, rotation or fall: while the piece rests, start the
; wait again (up to MAX_RESETS times on this row). Then the original
; instruction, ld hl,$AF81.
lock_reset::
        ld      a, [OPTIONS]
        bit     OPT_LOCK, a
        jr      z, .done
        ld      a, [LD_TIMER]
        cp      LOCK_FRAMES     ; full: not resting, nothing to reset
        jr      nc, .done
        ld      a, [LD_COUNT]
        cp      MAX_RESETS
        jr      nc, .done
        inc     a
        ld      [LD_COUNT], a
        ld      a, LOCK_FRAMES
        ld      [LD_TIMER], a
.done
        ld      hl, PIECE_Y
        ret

; Soft or hard drop could not move the piece down. Original: dec [$AF81], then
; lock. Returns Z to lock now (lock delay off, or hard drop with Up), NZ to
; leave the piece resting for lock_tick.
lock_land::
        ld      hl, PIECE_Y
        dec     [hl]
        ld      a, [OPTIONS]
        bit     OPT_LOCK, a
        ret     z
        ldh     a, [$ff83]
        bit     6, a            ; Up: hard drop locks at once
        jr      nz, .lock
        ld      a, 1
        and     a               ; NZ
        ret
.lock
        xor     a
        ret
