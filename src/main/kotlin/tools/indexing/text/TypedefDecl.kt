package tools.indexing.text

import tools.indexing.DeclarationHolder
import tools.indexing.text.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

class TypedefDecl(
    identifier: String, parent: DeclarationHolder,
    parseTree: GuardedParseTree,
    source: TextUppaalPojo,
    val structure: Type
) : FieldDecl(identifier, parent, parseTree, source)