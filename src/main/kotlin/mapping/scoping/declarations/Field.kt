package mapping.scoping.declarations

import mapping.scoping.Declaration
import mapping.scoping.Type

class Field(identifier: String, val type: Type, val defaultValue: Any?) : Declaration(identifier) {

    // TODO: Perhaps make a tagging system where "parameterIndex" could be a tag? This would be easier to extend in the future
    constructor(identifier: String, type: Type, parameterIndex: Int) : this(identifier, type, null) {
        throw NotImplementedError()
    }
}