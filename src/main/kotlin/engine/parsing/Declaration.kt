package engine.parsing

interface Declaration {
    val type: Type
    val identifier: String
}

class TypedefDeclaration(
    override val identifier: String,
    override val type: Type
) : Declaration

class ChannelDeclaration(            // TODO: Determine if should be merged with VariableDeclaration
    override val type: Type,
    override val identifier: String
) : Declaration

class VariableDeclaration(
    override val type: Type,
    override val identifier: String,
    val initializer: String?
) : Declaration

class FunctionDeclaration(
    override val type: Type,
    override val identifier: String
) : Declaration

class PartialInstantiation(
    override val type: Type,
    override val identifier: String,
    val parameters: MutableList<String>,
    val rightHandSide: String
) : Declaration