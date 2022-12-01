fun String.jsonFy() = this.replace("\\", "\\\\").replace("\"", "\\\"")
fun String.unJsonFy() = this.replace("\\\"", "\"").replace("\\\\", "\\")

fun IntRange.offset(offset: Int) = (this.first + offset .. this.last + offset)
fun IntRange.length() = this.last - this.first + 1
fun IntRange.within(other: IntRange) = this.first >= other.first && this.last <= other.last
fun IntRange.overlaps(other: IntRange) = this.first <= other.last && other.first <= this.last