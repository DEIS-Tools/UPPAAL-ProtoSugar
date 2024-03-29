package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "label")
class Label() : TextUppaalPojo {
    companion object {
        const val KIND_SELECT = "select"
        const val KIND_GUARD = "guard"
        const val KIND_SYNC = "synchronisation"
        const val KIND_UPDATE = "assignment"
        const val KIND_COMMENT = "comment"
        const val KIND_INVARIANT = "invariant"
    }

    @field:Attribute(name = "kind")
    lateinit var kind: String

    @field:Attribute(name = "x")
    var x: Int = 0

    @field:Attribute(name = "y")
    var y: Int = 0

    @field:Text
    override var content: String = ""

    override var parent: UppaalPojo? = null

    constructor(kind: String, x: Int, y: Int, content: String = "") : this() {
        this.kind = kind
        this.x = x
        this.y = y
        this.content = content
    }

    override fun toString() = "$kind: $content"

    fun clone(): Label = Label(kind, x, y, content)
}