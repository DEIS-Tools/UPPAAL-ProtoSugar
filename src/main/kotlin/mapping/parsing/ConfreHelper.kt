package mapping.parsing

class ConfreHelper {
    companion object {
        val expressionGrammar = """
                IDENT = [_a-zA-Z][_a-zA-Z0-9]*
                INT   = [0-9]+
                BOOL  = true|false
            
                Expression   :== [Unary] ('(' Expression ')' | ExtendedTerm {'.' [ExtendedTerm]} | Quantifier) [(Binary|Assignment) Expression] .
                ExtendedTerm :== Term [{Subscript} | '(' [Expression {',' Expression}] ')'] .
                Term         :== IDENT | INT | BOOL | 'deadlock' .
                Subscript    :== '[' [Expression] ']' .
                
                Quantifier :== ('forall' | 'exists' | 'sum') '(' IDENT ':' Type ')' Expression .
                
                Unary      :== '+' | '-' | '!' | 'not' .
                Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                             | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
                Assignment :== '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' .
                
                Type :== ['const'|'meta'] IDENT ['[' [Expression] ',' [Expression] ']' | '(' [Type] {',' [Type]} ')'] .
            """.trimIndent()

        val partialInstantiationConfre = Confre("""
            Instantiation :== IDENT ['(' [Params] ')'] '=' IDENT '(' [[Expression] {',' [Expression]}] ')' ';'.
            Params :== [Type ['&'] IDENT {Subscript}] {',' [Type ['&'] IDENT {Subscript}]} .
            
            $expressionGrammar
        """.trimIndent())

        val constIntConfre = Confre(
            """
            ConstInt :== 'const' 'int' ['[' [Expression] ',' [Expression] ']'] IDENT '=' Expression ';' .
            
            $expressionGrammar
        """.trimIndent())

        val expressionAssignmentListConfre = Confre("""
            ExprList :== Expression {',' Expression} .
            
            $expressionGrammar
        """.trimIndent())

        val systemLineConfre = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            
            System :== 'system' IDENT {',' IDENT} .
        """.trimIndent())
    }
}