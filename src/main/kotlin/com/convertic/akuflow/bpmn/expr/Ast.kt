package com.convertic.akuflow.bpmn.expr

sealed interface Expr {
    fun eval(vars: Map<String, Any?>): Any?
}

data class Literal(val value: Any?) : Expr {
    override fun eval(vars: Map<String, Any?>): Any? = value
}

data class Variable(val name: String) : Expr {
    override fun eval(vars: Map<String, Any?>): Any? = vars[name]
}

data class Unary(val op: TokenType, val right: Expr) : Expr {
    override fun eval(vars: Map<String, Any?>): Any? {
        val v = right.eval(vars)
        return when (op) {
            TokenType.BANG -> !(v as? Boolean ?: false)
            else -> error("Unsupported unary op $op")
        }
    }
}

data class Binary(val left: Expr, val op: TokenType, val right: Expr) : Expr {
    override fun eval(vars: Map<String, Any?>): Any? {
        val l = left.eval(vars)
        val r = right.eval(vars)

        return when (op) {
            TokenType.AND_AND -> (l as? Boolean ?: false) && (r as? Boolean ?: false)
            TokenType.OR_OR -> (l as? Boolean ?: false) || (r as? Boolean ?: false)
            TokenType.EQUAL_EQUAL -> l == r
            TokenType.BANG_EQUAL -> l != r
            TokenType.LESS,
            TokenType.LESS_EQUAL,
            TokenType.GREATER,
            TokenType.GREATER_EQUAL -> compare(l, r, op)
            else -> error("Unsupported binary op $op")
        }
    }

    private fun compare(l: Any?, r: Any?, op: TokenType): Boolean {
        require(l != null && r != null) { "Cannot compare null values: $l $op $r" }

        return when {
            l is Number && r is Number -> {
                val lv = l.toDouble()
                val rv = r.toDouble()
                when (op) {
                    TokenType.LESS -> lv < rv
                    TokenType.LESS_EQUAL -> lv <= rv
                    TokenType.GREATER -> lv > rv
                    TokenType.GREATER_EQUAL -> lv >= rv
                    else -> error("Invalid comparison op $op")
                }
            }
            l is String && r is String -> {
                val cmp = l.compareTo(r)
                when (op) {
                    TokenType.LESS -> cmp < 0
                    TokenType.LESS_EQUAL -> cmp <= 0
                    TokenType.GREATER -> cmp > 0
                    TokenType.GREATER_EQUAL -> cmp >= 0
                    else -> error("Invalid comparison op $op")
                }
            }
            else -> error("Cannot compare values of different or unsupported types: $l and $r")
        }
    }
}
