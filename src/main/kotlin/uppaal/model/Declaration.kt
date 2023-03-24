package uppaal.model

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "declaration")
class Declaration() : TextUppaalPojo {
    @field:Text()
    override var content: String = ""

    constructor(content: String) : this() {
        this.content = content
    }

    fun clone(): Declaration = Declaration(content)
}