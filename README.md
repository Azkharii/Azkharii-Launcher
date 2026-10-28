# Azkharii Launcher

Launcher cinematográfico para Android TV, con carruseles de aplicaciones, biblioteca, aplicaciones fijadas y soporte para el contenido Continuar viendo publicado por el proveedor del sistema.

## Builds y releases

- Build de prueba: los workflows de GitHub Actions validan el código, ejecutan las pruebas y publican un artefacto APK de depuración.
- APK estable: https://github.com/Azkharii/Azkharii-Launcher/releases/latest. El workflow publica el APK estable y su checksum cuando los cambios llegan a main y la clave de firma persistente está configurada.
- Actualizaciones en TV: la aplicación estable consulta el feed público de Releases y avisa cuando encuentra una versión más nueva. Valida tamaño y SHA-256 antes de abrir el instalador de Android; la instalación siempre requiere aprobación del usuario.

La distribución estable requiere configurar los cuatro secretos de firma descritos en docs/RELEASING.md. No se debe subir el keystore o las contraseñas al repositorio.

## Desarrollo

- Instrucciones del proyecto: AGENTS.md
- Flujo de releases, firma y actualizaciones: docs/RELEASING.md
- Informe de verificaciones: docs/TEST_REPORT.md

El repositorio privado de respaldo se mantiene separado; los cambios y las publicaciones de este flujo van en Azkharii/Azkharii-Launcher.
