# Actualizaciones automáticas de Cinematic TV

## Estado actual

- El repositorio público es `Azkharii/Azkharii-Launcher`.
- `UpdateManager` consulta `https://api.github.com/repos/Azkharii/Azkharii-Launcher/releases/latest`; no requiere un token de GitHub y no debe incluirse ningún secreto en el APK.
- El actualizador valida el nombre del APK, el SHA-256 publicado por GitHub, el identificador del paquete y el código de versión antes de abrir el instalador de Android.
- Android siempre solicita la aprobación final de instalación; no se intenta instalar silenciosamente.
- **Todavía no hay una Release publicada.** El workflow de publicación necesita los cuatro secretos de firma persistente descritos en `docs/RELEASING.md`.
- El actualizador se ejecuta únicamente en la variante estable. La variante `debug` no se autoactualiza.

## Dos requisitos distintos para actualizar encima de una instalación existente

1. **Identidad del paquete:** Android solo puede actualizar una instalación si el APK tiene el mismo `applicationId`. La variante debug usa `com.cinematic.tvlauncher.debug`; la estable usa `com.cinematic.tvlauncher`. No son actualizaciones entre sí.
2. **Firma:** el APK nuevo debe estar firmado con el mismo certificado que el APK instalado. Los runners de GitHub Actions generan por defecto su propia clave debug; por eso no se debe asumir que dos artefactos debug de ejecuciones diferentes se pueden instalar uno encima del otro.

El `versionCode` nuevo debe ser superior al instalado. En una instalación manual por ADB, `adb install -r archivo.apk` solicita reemplazar la aplicación existente, pero no evita los errores por firma incompatible ni por un `versionCode` inferior.

## Publicación estable

El workflow `.github/workflows/release.yml` se ejecuta en `main`. Con la clave persistente configurada, compila `cinematic-tv-launcher-release.apk`, publica un tag `v1.0.N` y adjunta el APK y su checksum SHA-256 a GitHub Releases.

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
