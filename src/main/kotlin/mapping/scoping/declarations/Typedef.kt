package mapping.scoping.declarations

import mapping.scoping.Declaration
import mapping.scoping.Type

class Typedef(identifier: String, val structure: Type) : Declaration(identifier)