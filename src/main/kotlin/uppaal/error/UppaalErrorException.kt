package uppaal.error

/** This exception is used to "return" uppaal errors from complex chains of function calls without having to insert logic
 * to return nullable errors when this results in unreadable code. **/
class UppaalErrorException(val uppaalError: UppaalError) : Exception()