package uppaal.model

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "parameter")
class Parameter() : TextUppaalPojo {
    @field:Text
    override var content: String = ""

    constructor(content: String) : this() {
        this.content = content
    }

    override fun toString() = content
}