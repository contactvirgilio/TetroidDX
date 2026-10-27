; Tetris DX "Skip Celebrations", switchable at runtime.
;
; Based on "Skip Celebrations" v1.0 by llakssz (romhacking.net hack #4734).
; At bank 1 $4568 and $456F the game loads $10 or $11 into A and stores it to
; $C0A1, the next game state, which starts a celebration. llakssz's patch
; changes both values to $08, the state the game uses when there is nothing
; to celebrate. Here each load+store is moved into a routine that picks the
; original value or $08 depending on the OPTIONS byte, which the app sets from
; its menu. With the bit clear the game behaves like the original.
;
; Original code (checked by the workflow before patching):
;   $4568: ld a,$10 / ld [$C0A1],a / jr $457F
;   $456F: ld a,$11 / ld [$C0A1],a / xor a ...

def OPTIONS                   equ $7fff   ; shared with harddrop_toggle.asm
def OPT_SKIPCELEB             equ 4       ; bit 4: Skip Celebrations on
def NEXT_STATE                equ $c0a1
def SKIP_STATE                equ $08
def HOOK_ADDR                 equ $7f4a   ; free space before the Don't Panic hook

SECTION "skipceleb_site_a", ROMX[$4568], BANK[1]
        jp      skipceleb_a     ; replaces ld a,$10 / ld [$C0A1],a (5 bytes)
        nop
        nop

SECTION "skipceleb_site_b", ROMX[$456f], BANK[1]
        jp      skipceleb_b     ; replaces ld a,$11 / ld [$C0A1],a (5 bytes)
        nop
        nop

SECTION "skipceleb_hooks", ROMX[HOOK_ADDR], BANK[1]
skipceleb_a::
        ld      a, [OPTIONS]
        bit     OPT_SKIPCELEB, a
        ld      a, $10
        jr      z, .store
        ld      a, SKIP_STATE
.store
        ld      [NEXT_STATE], a
        jp      $456d           ; continue at jr $457F

skipceleb_b::
        ld      a, [OPTIONS]
        bit     OPT_SKIPCELEB, a
        ld      a, $11
        jr      z, .store
        ld      a, SKIP_STATE
.store
        ld      [NEXT_STATE], a
        jp      $4574           ; continue at xor a
