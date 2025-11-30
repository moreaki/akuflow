package com.convertic.akuflow.bpmn.expr

class Parser(private val tokens: List<Token>) {

    private var current = 0

    fun parse(): Expr {
        val expr = expression()
        expect(TokenType.EOF)
        return expr
    }

    private fun expression(): Expr = or()

    private fun or(): Expr {
        var expr = and()
        while (match(TokenType.OR_OR)) {
            val op = previous().type
            val right = and()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun and(): Expr {
        var expr = equality()
        while (match(TokenType.AND_AND)) {
            val op = previous().type
            val right = equality()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun equality(): Expr {
        var expr = comparison()
        while (match(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL)) {
            val op = previous().type
            val right = comparison()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun comparison(): Expr {
        var expr = unary()
        while (match(TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL)) {
            val op = previous().type
            val right = unary()
            expr = Binary(expr, op, right)
        }
        return expr
    }

    private fun unary(): Expr {
        if (match(TokenType.BANG)) {
            val op = previous().type
            val right = unary()
            return Unary(op, right)
        }
        return primary()
    }

    private fun primary(): Expr {
        if (match(TokenType.FALSE)) return Literal(false)
        if (match(TokenType.TRUE)) return Literal(true)
        if (match(TokenType.NUMBER)) return Literal(previous().literal)
        if (match(TokenType.STRING)) return Literal(previous().literal)
        if (match(TokenType.IDENT)) return Variable(previous().lexeme)
        if (match(TokenType.LPAREN)) {
            val expr = expression()
            expect(TokenType.RPAREN)
            return expr
        }
        error("Expected expression but found ${peek().type}")
    }

    private fun match(vararg types: TokenType): Boolean {
        for (t in types) {
            if (check(t)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun check(type: TokenType): Boolean {
        if (isAtEnd()) return false
        return peek().type == type
    }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd() = peek().type == TokenType.EOF
    private fun peek() = tokens[current]
    private fun previous() = tokens[current - 1]

    private fun expect(type: TokenType) {
        if (check(type)) {
            advance()
        } else {
            error("Expected token $type but found ${peek().type}")
        }
    }
}
