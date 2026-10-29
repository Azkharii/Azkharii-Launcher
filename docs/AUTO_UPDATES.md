# Comprobación manual de actualizaciones de Cinematic TV

## Estado actual

- El repositorio público es `Azkharii/Azkharii-Launcher`.
- `UpdateManager` consulta `https://api.github.com/repos/Azkharii/Azkharii-Launcher/releases/latest`; no requiere un token de GitHub y no debe incluirse ningún secreto en el APK.
- El comprobador lee SHA-256 del metadato del activo o del archivo de checksum publicado por el workflow; antes de abrir el instalador valida el nombre del APK, el tamaño, el SHA-256, el identificador del paquete y el código de versión.
- Android siempre solicita la aprobación final de instalación; no se intenta instalar silenciosamente.
- La publicación estable utiliza la clave de firma persistente configurada mediante los cuatro secretos descritos en `docs/RELEASING.md`; nunca debe publicarse una APK con una clave efímera.
- La comprobación se inicia desde la flecha sin recuadro junto a «Entradas»; no hay temporizador ni consultas automáticas al reanudar. Las compilaciones debug pueden consultar releases y mostrar el enlace, pero no se instalan encima de la variante estable.
- El aviso solo muestra las versiones visible instalada y disponible (por ejemplo, `1.0.3 → 1.0.4`); no expone el `versionCode` ni los detalles del commit.
- Una comprobación manual indica si ya estás actualizado, si hay una versión más nueva o si la release no puede verificarse. Los fallos se pueden reintentar pulsando de nuevo la flecha.

## Dos requisitos distintos para actualizar encima de una instalación existente

1. **Identidad del paquete:** Android solo puede actualizar una instalación si el APK tiene el mismo `applicationId`. La variante debug usa `com.cinematic.tvlauncher.debug`; la estable usa `com.cinematic.tvlauncher`. No son actualizaciones entre sí.
2. **Firma:** el APK nuevo debe estar firmado con el mismo certificado que el APK instalado. Los runners de GitHub Actions generan por defecto su propia clave debug; por eso no se debe asumir que dos artefactos debug de ejecuciones diferentes se pueden instalar uno encima del otro.

El `versionCode` nuevo debe ser superior al instalado. En una instalación manual por ADB, `adb install -r archivo.apk` solicita reemplazar la aplicación existente, pero no evita los errores por firma incompatible ni por un `versionCode` inferior.

## Publicación estable

El workflow `.github/workflows/release.yml` se ejecuta en `main`. Con la clave persistente configurada, compila `cinematic-tv-launcher-release.apk`, publica un tag `v1.0.N+CODE`, muestra `1.0.N` como versión legible y adjunta el APK y su checksum SHA-256 a GitHub Releases.

Antes de activar la primera publicación hay que determinar qué certificado firma la aplicación que ya está instalada en el televisor. No se debe sustituir la firma ni cambiar de variante a ciegas, porque Android podría rechazar la actualización y la desinstalación puede borrar los datos locales.

Los cuatro secretos necesarios son:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Si el launcher instalado en el televisor es la variante debug, primero hay que decidir cómo conservar su identidad y certificado. Publicar una Release estable con el identificador `com.cinematic.tvlauncher` no actualizará `com.cinematic.tvlauncher.debug`.

## Política de seguridad

- No guardar keystores, contraseñas ni tokens en el repositorio.
- No incrustar un Personal Access Token en el APK.
- No desinstalar la versión instalada para “forzar” una actualización sin confirmar antes cómo se preservarán los datos y la firma.
- No afirmar que una Release está disponible hasta comprobarla en GitHub.
