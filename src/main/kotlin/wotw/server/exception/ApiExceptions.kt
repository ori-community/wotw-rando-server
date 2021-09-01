package wotw.server.exception

class AlreadyExistsException(message: String? = null) : Exception(message)
class UnauthorizedException : Exception()
class ServerConfigurationException(message: String? = null) : Exception(message)
class ForbiddenException(scopes: Collection<String> = emptySet()) :
    Exception("The requested resource cannot be accessed".also {
        if (scopes.isEmpty())
            it
        else {
            it + "missing scopes: ${scopes.toList().sorted()}"
        }
    })
