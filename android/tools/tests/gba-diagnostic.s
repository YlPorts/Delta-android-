// Original CC0 diagnostic; no proprietary logo or BIOS.
.syntax unified
.arm
.section .text
.global _start
_start:
    b program
.org 0xa0
.ascii "DELTA TEST  "
.ascii "DLTA"
.ascii "00"
.byte 0x96,0,0
.org 0xc0
program:
    ldr r0, =0x04000000
    ldr r1, =0x0403
    strh r1, [r0]
    ldr r5, =0x0e000000
    mov r1, #0x42
    strb r1, [r5]
    mov r4, #0
    strb r4, [r5, #1]
    mov r1, #0x80
    strh r1, [r0, #0x84]
    ldr r1, =0x1177
    strh r1, [r0, #0x80]
    mov r1, #2
    strh r1, [r0, #0x82]
    ldr r1, =0xf080
    strh r1, [r0, #0x62]
    ldr r1, =0x8700
    strh r1, [r0, #0x64]
    ldr r6, =0x06000000
    ldr r7, =0x06012c00
    mov r1, #0
fill:
    strh r1, [r6], #2
    add r1, r1, #1
    cmp r6, r7
    bne fill
loop:
    ldrh r1, [r0, #6]
    cmp r1, #160
    blo loop
    add r4, r4, #1
    strb r4, [r5, #1]
    ldr r8, =0x04000130
    ldrh r1, [r8]
    mvn r1, r1
    and r1, r1, #1
    strb r1, [r5, #3]
    ldr r6, =0x06000000
    strh r4, [r6]
wait:
    ldrh r1, [r0, #6]
    cmp r1, #160
    bhs wait
    b loop
.ltorg
.org 0x1000
.ascii "SRAM_V110"
