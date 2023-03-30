package tools.indexing.text.types

abstract class Modifier(val name: String)

class Meta : Modifier("meta")
class Const : Modifier("const")
