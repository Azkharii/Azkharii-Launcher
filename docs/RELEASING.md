# Publicación y actualización de Cinematic TV

## Flujo de publicación

- Los pushes y pull requests siguen usando el workflow de build/validación existente.
- Después de integrar los cambios en `main`, `.github/workflows/release.yml` valida el proyecto y crea una versión estable firmada.
- El workflow genera etiquetas `v1.0.N`, con `versionName=1.0.N` y `versionCode=N`; N es el número creciente del workflow de publicación.
- El APK de la versión estable se publica con el nombre exacto `cinematic-tv-launcher-release.apk`. El actualizador comprueba la versión, el tamaño, el identificador de paquete y el SHA-256 antes de abrir el instalador de Android.
- Android requiere que una actualización conserve la misma clave de firma y que el usuario apruebe el instalador. No se intenta ni promete instalar silenciosamente.

## Preparación única de firma

Crear una clave de release fuera del repositorio y guardarla de forma segura. No se debe subir el keystore ni las contraseñas a GitHub como archivos del código.

En los secretos de Actions del repositorio se deben configurar estos cuatro valores:

- `ANDROID_KEYSTORE_BASE64`: keystore codificado en Base64 como una sola línea.
- `ANDROID_KEYSTORE_PASSWORD`: contraseña del keystore.
- `ANDROID_KEY_ALIAS`: alias de la clave de firma.
- `ANDROID_KEY_PASSWORD`: contraseña de la clave.

La clave de firma debe conservarse a largo plazo: si se pierde o se sustituye, Android no aceptará el APK nuevo como actualización de una instalación firmada con la clave anterior. Primero hay que instalar una versión estable de release; el paquete debug usa un identificador distinto (`com.cinematic.tvlauncher.debug`) y no se actualiza con la versión release.

## Límite de acceso del feed de actualización

El actualizador de la app consulta `https://api.github.com/repos/Azkharii/-hisense-u75k-cinematic-launcher/releases/latest` sin credenciales. GitHub no permite que la app acceda de forma anónima a Releases de un repositorio privado: la consulta devuelve 404. Por tanto, la detección automática solo funciona si ese feed y el APK son públicamente descargables o si se configura un endpoint de distribución pública equivalente.

No incrustar un Personal Access Token (PAT) ni otro secreto de GitHub en el APK. Eso expondría el repositorio y el token a cualquiera que extraiga la app. Este proyecto no cambia la visibilidad del repositorio automáticamente.

## Seguir viendo: fuente de datos

La fila usa dos fuentes legítimas: filas CONTINUE/NEXT que expone el proveedor TV y metadatos locales capturados por sesiones multimedia o notificaciones multimedia compatibles. Si el diagnóstico da `rows=0`, el proveedor está accesible pero no contiene filas Watch Next; no hay nada que corregir en los filtros. El fallback local solo puede registrar títulos/progreso que la aplicación multimedia exponga a Android. Engage es una integración de publicador dentro de la propia app multimedia y no es una API pública para leer el historial privado agregado de Google TV.

Para diagnosticar el televisor, abrir el diálogo completo y conservar ambos apartados: los contadores de Watch Next y la línea “Sesiones y notificaciones” (listenerConnected, activeSessions, titledSessions, storedTitles, mediaNotifications, activePackages y lastCapture*). Si el acceso a notificaciones está desactivado, activarlo desde los ajustes abiertos por la tarjeta vacía y reproducir un título durante al menos un minuto en una app compatible; luego volver al launcher y revisar el diagnóstico. Si activeSessions/titledSessions/storedTitles continúan en cero, esa app/firmware no está exponiendo metadatos utilizables.
