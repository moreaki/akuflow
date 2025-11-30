package com.convertic.akuflow.bpmn.expr

class ExpressionEvaluator {

    fun evaluateBoolean(rawExpr: String, vars: Map<String, Any?>): Boolean {
        val expr = unwrap(rawExpr).trim()
        if (expr.isEmpty()) error("Empty expression: '$rawExpr'")
        val lexer = Lexer(expr)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val ast = parser.parse()
        val result = ast.eval(vars)
        return (result as? Boolean)
            ?: error("Expression '$rawExpr' did not evaluate to Boolean (got '$result')")
    }

    fun evaluateAny(rawExpr: String, vars: Map<String, Any?>): Any? {
        val expr = unwrap(rawExpr).trim()
        val lexer = Lexer(expr)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val ast = parser.parse()
        return ast.eval(vars)
    }

    private fun unwrap(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length - 1)
        }
        return trimmed
    }
}
