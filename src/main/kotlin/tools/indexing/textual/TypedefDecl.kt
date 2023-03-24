package tools.indexing.textual

import tools.indexing.DeclarationBase
import tools.indexing.Textual
import tools.indexing.types.Type
import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

class TypedefDecl(identifier: String,
                  override val parseTree: GuardedParseTree,
                  override val source: TextUppaalPojo,
                  val structure: Type)
    : DeclarationBase(identifier), Textual