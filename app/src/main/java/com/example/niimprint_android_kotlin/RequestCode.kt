package com.example.niimprint_android_kotlin

enum class RequestCode(val code: Int) {
    SET_LABEL_DENSITY(0x21),
    SET_LABEL_TYPE(0x23),
    START_PRINT(0x01),
    END_PRINT(0xF3),
    START_PAGE_PRINT(0x03),
    END_PAGE_PRINT(0xE3),
    SET_DIMENSION(0x13),
    GET_PRINT_STATUS(0xA3)
}
