package com.convertic.akuflow.bpmn.expr

enum class TokenType {
    LPAREN, RPAREN,
    LESS, GREATER,
    PLUS, MINUS, STAR, SLASH,
    BANG,
    LESS_EQUAL, GREATER_EQUAL,
    EQUAL_EQUAL, BANG_EQUAL,
    AND_AND, OR_OR,
    IDENT, NUMBER, STRING,
    TRUE, FALSE,
    EOF
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val literal: Any? = null
)
