package com.convertic.akuflow.bpmn.expr

class Lexer(private val source: String) {

    private val tokens = mutableListOf<Token>()
    private var start = 0
    private var current = 0

    fun tokenize(): List<Token> {
        while (!isAtEnd()) {
            start = current
            scanToken()
        }
        tokens += Token(TokenType.EOF, "", null)
        return tokens
    }

    private fun isAtEnd() = current >= source.length

    private fun scanToken() {
        when (val c = advance()) {
            ' ', '\t', '\r', '\n' -> {}
            '(' -> add(TokenType.LPAREN)
            ')' -> add(TokenType.RPAREN)
            '!' -> add(if (match('=')) TokenType.BANG_EQUAL else TokenType.BANG)
            '=' -> if (match('=')) add(TokenType.EQUAL_EQUAL) else error("Unexpected '='")
            '<' -> add(if (match('=')) TokenType.LESS_EQUAL else TokenType.LESS)
            '>' -> add(if (match('=')) TokenType.GREATER_EQUAL else TokenType.GREATER)
            '&' -> if (match('&')) add(TokenType.AND_AND) else error("Unexpected '&'")
            '|' -> if (match('|')) add(TokenType.OR_OR) else error("Unexpected '|'")
            '"', '\'' -> string(c)
            in '0'..'9' -> number()
            else -> {
                if (isAlpha(c)) {
                    identifier()
                } else {
                    error("Unexpected character '$c' in expression: '$source'")
                }
            }
        }
    }

    private fun advance(): Char = source[current++]

    private fun match(expected: Char): Boolean {
        if (isAtEnd()) return false
        if (source[current] != expected) return false
        current++
        return true
    }

    private fun add(type: TokenType, literal: Any? = null) {
        val text = source.substring(start, current)
        tokens += Token(type, text, literal)
    }

    private fun string(quote: Char) {
        while (!isAtEnd() && source[current] != quote) current++
        if (isAtEnd()) error("Unterminated string in expression: '$source'")
        current++
        val value = source.substring(start + 1, current - 1)
        add(TokenType.STRING, value)
    }

    private fun number() {
        while (!isAtEnd() && source[current].isDigit()) current++
        if (!isAtEnd() && source[current] == '.') {
            current++
            while (!isAtEnd() && source[current].isDigit()) current++
        }
        val text = source.substring(start, current)
        val literal = text.toDouble()
        tokens += Token(TokenType.NUMBER, text, literal)
    }

    private fun identifier() {
        while (!isAtEnd() && isAlphaNumeric(source[current])) current++
        val text = source.substring(start, current)
        val type = when (text) {
            "true" -> TokenType.TRUE
            "false" -> TokenType.FALSE
            else -> TokenType.IDENT
        }
        tokens += Token(type, text, null)
    }

    private fun isAlpha(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isAlphaNumeric(c: Char) = isAlpha(c) || c.isDigit()
}
