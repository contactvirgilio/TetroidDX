; Piece randomizer choice for Tetris DX (Tetroid DX), switchable at runtime.
;
;   0  Tetris DX's own randomizer (the original code runs unchanged)
;   1  7-bag: every run of 7 pieces holds each piece once
;   2  35-Bag (TGM-style): modelled on the Tetris The Grand Master 3
;      randomizer. The 35-piece pool (5 of each) and putting the longest-unseen
;      piece back after each draw follow tetris.wiki / Hard Drop wiki; the
;      4-piece history (starting S Z S Z), up to 6 rolls and the I/T/J/L first
;      piece follow the commonly cited reference code. Not a byte-exact copy
;      of the arcade game, whose known quirks are not reproduced.
;
; The mode is read from bank 0 $3FFF (RANDOMIZER), which the app writes from
; its menu. The original game picks the next piece in bank 1 at $58BC-$58E7
; and stores it at $58E8 (ld [$AF84],a). The hook replaces the first
; instruction there with a jump; with mode 0 it runs that instruction and
; carries on exactly as before.
;
; Work RAM $CF80-$CFB3 holds the randomizer state (unused by the game).
; The state starts over whenever the mode changes or the RAM is cleared.
;
; Piece numbers: 0 I, 1 T, 2 Z, 3 S, 4 J, 5 L, 6 O.

def RANDOMIZER                equ $3fff   ; bank 0, set by the app
def NEXT_PIECE                equ $af84
def DX_COUNTER                equ $af83

def RS_MAGIC                  equ $cf80   ; $A5 once set up
def RS_RNG                    equ $cf81   ; 2 bytes
def RS_BAG                    equ $cf83   ; 7-bag: bits of the pieces left
def RS_HIST                   equ $cf84   ; 4 bytes, oldest first
def RS_ORDER_N                equ $cf88
def RS_ORDER                  equ $cf89   ; 7 bytes, longest unseen first
def RS_POOL                   equ $cf90   ; 35 bytes
def RS_MODE                   equ $cfb3

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
        jp      $58e8           ; ld [NEXT_PIECE],a and on as normal

SECTION "rand_bank0", ROM0[$3a00]
; Returns the next piece in a.
rand_next::
        ld      a, [RS_MAGIC]
        cp      $a5
        jr      nz, .init
        ld      a, [RANDOMIZER]
        ld      b, a
        ld      a, [RS_MODE]
        cp      b
        jr      z, .ready
.init
        call    rand_init
        ld      a, [RANDOMIZER]
        cp      2
        jr      nz, .ready
        ; 35-bag first piece: I, T, J or L
        ld      a, 4
        call    rand_mod
        ld      e, a
        ld      d, 0
        ld      hl, rand_first
        add     hl, de
        ld      a, [hl]
        ld      d, a
        call    rand_push_history
        ld      a, d
        ret
.ready
        ld      a, [RANDOMIZER]
        cp      1
        jr      z, rand_bag7
        jr      rand_tgm3

rand_first:
        db      0, 1, 4, 5

; ---- 7-bag
rand_bag7:
        ld      a, [RS_BAG]
        and     $7f
        jr      nz, .pick
        ld      a, $7f
        ld      [RS_BAG], a
.pick
        ld      a, 7
        call    rand_mod
        ld      d, a
        ld      b, a
        inc     b
        ld      a, 1
.shift
        dec     b
        jr      z, .mask
        add     a
        jr      .shift
.mask
        ld      e, a
        ld      a, [RS_BAG]
        and     e
        jr      z, .pick        ; already dealt from this bag
        ld      a, e
        cpl
        ld      e, a
        ld      a, [RS_BAG]
        and     e
        ld      [RS_BAG], a
        ld      a, d
        ret

; ---- 35-bag (TGM3)
rand_tgm3:
        ld      c, 0            ; roll number
.roll
        ld      a, 35
        call    rand_mod
        ld      e, a            ; pool index
        add     LOW(RS_POOL)
        ld      l, a
        ld      h, HIGH(RS_POOL)
        ld      a, [hl]
        ld      d, a            ; piece
        call    rand_in_history
        and     a
        jr      z, .done
        ld      a, c
        cp      5
        jr      z, .done
        ld      a, [RS_ORDER_N]
        and     a
        jr      z, .next
        ld      a, [RS_ORDER]
        ld      [hl], a
.next
        inc     c
        jr      .roll
.done
        call    rand_order_remove
        ld      a, [RS_ORDER_N]
        add     LOW(RS_ORDER)
        ld      l, a
        ld      h, HIGH(RS_ORDER)
        ld      [hl], d
        ld      a, [RS_ORDER_N]
        inc     a
        ld      [RS_ORDER_N], a
        ld      a, e
        add     LOW(RS_POOL)
        ld      l, a
        ld      h, HIGH(RS_POOL)
        ld      a, [RS_ORDER]
        ld      [hl], a
        call    rand_push_history
        ld      a, d
        ret

; a = 1 if piece d is in the history. Keeps bc and hl.
rand_in_history:
        push    hl
        push    bc
        ld      hl, RS_HIST
        ld      b, 4
.loop
        ld      a, [hl+]
        cp      d
        jr      z, .yes
        dec     b
        jr      nz, .loop
        xor     a
        jr      .out
.yes
        ld      a, 1
.out
        pop     bc
        pop     hl
        ret

; Removes piece d from the unseen order, if it is there.
rand_order_remove:
        ld      hl, RS_ORDER
        ld      a, [RS_ORDER_N]
        and     a
        ret     z
        ld      b, a
.find
        ld      a, [hl]
        cp      d
        jr      z, .found
        inc     hl
        dec     b
        jr      nz, .find
        ret
.found
        dec     b
        jr      z, .count
.shift
        inc     hl
        ld      a, [hl-]
        ld      [hl+], a
        dec     b
        jr      nz, .shift
.count
        ld      a, [RS_ORDER_N]
        dec     a
        ld      [RS_ORDER_N], a
        ret

; History: drop the oldest, add piece d.
rand_push_history:
        ld      hl, RS_HIST
        ld      a, [RS_HIST + 1]
        ld      [hl+], a
        ld      a, [RS_HIST + 2]
        ld      [hl+], a
        ld      a, [RS_HIST + 3]
        ld      [hl+], a
        ld      [hl], d
        ret

rand_init:
        ld      a, $a5
        ld      [RS_MAGIC], a
        ld      a, [RANDOMIZER]
        ld      [RS_MODE], a
        ldh     a, [$ff04]
        ld      [RS_RNG], a
        ld      a, $5a
        ld      [RS_RNG + 1], a
        xor     a
        ld      [RS_BAG], a
        ld      [RS_ORDER_N], a
        ld      hl, RS_HIST     ; history starts as S, Z, S, Z
        ld      a, 3
        ld      [hl+], a
        ld      a, 2
        ld      [hl+], a
        ld      a, 3
        ld      [hl+], a
        ld      a, 2
        ld      [hl], a
        ld      hl, RS_POOL     ; five of each piece
        ld      b, 35
        ld      c, 0
.fill
        ld      a, c
        ld      [hl+], a
        inc     c
        ld      a, c
        cp      7
        jr      nz, .more
        ld      c, 0
.more
        dec     b
        jr      nz, .fill
        ret

; a = random number from 0 to a-1 (a = 1..128). Keeps bc, de, hl.
rand_mod:
        push    bc
        ld      c, a
        ld      b, 0
        xor     a
.limit
        add     c               ; b = largest multiple of n below 256
        jr      c, .again
        ld      b, a
        jr      .limit
.again
        call    rand_byte
        cp      b
        jr      nc, .again
.mod
        cp      c
        jr      c, .done
        sub     c
        jr      .mod
.done
        pop     bc
        ret

; xorshift 7/9/8 (John Metcalf), stirred with the DIV timer. Keeps hl.
rand_byte:
        push    hl
        ld      a, [RS_RNG]
        ld      l, a
        ld      a, [RS_RNG + 1]
        ld      h, a
        ldh     a, [$ff04]
        xor     l
        ld      l, a
        or      h
        jr      nz, .go
        inc     l
.go
        ld      a, h
        rra
        ld      a, l
        rra
        xor     h
        ld      h, a
        ld      a, l
        rra
        ld      a, h
        rra
        xor     l
        ld      l, a
        xor     h
        ld      h, a
        ld      [RS_RNG + 1], a
        ld      a, l
        ld      [RS_RNG], a
        ld      a, h
        pop     hl
        ret
