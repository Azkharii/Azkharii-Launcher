# Plan de trabajo y reconciliación

## Estado de referencia (2026-10-26)

- Repositorio: `Azkharii/-hisense-u75k-cinematic-launcher` (privado).
- Rama principal: `main`.
- Commit publicado de referencia: `80fd8637f1c7279762d47a4a45115ac7de36465f` — `Fix duplicate Kotlin standard library classes`.
- La ejecución de GitHub Actions asociada a ese commit terminó con resultado `success` (run `40272177239`).
- Artifact disponible: `cinematic-tv-launcher-debug`, 3,513,184 bytes, creado el 2026-10-26. Esto acredita que CI produjo un APK para ese commit; no acredita instalación ni pruebas en el televisor.
- Se creó la rama de trabajo `feature/reconcile-and-cinematic-ui` desde el commit de `main`. Los cambios nuevos se aislarán en esa rama; `main` no se modifica directamente.

## Reconciliación antes de implementar

En la sesión previa quedaron cambios locales sin publicar en una copia de Termux. El estado anotado incluía cambios en:
- `AppCarouselView.java`
- `LauncherConfig.java`
- `config.xml`
- `LauncherConfigTest.java`
- archivos `.bak` de configuración y un directorio `launcher/app/build/` sin seguimiento.

Esos cambios no aparecen en el commit remoto de referencia. Como la copia local de Termux no está disponible desde esta conexión, se deben reconstruir a partir de las decisiones y requisitos ya registrados y revisar cada diferencia antes de publicar. No se copiarán binarios generados ni se sobrescribirán archivos de forma ciega.

## Orden de ejecución

1. **Reconciliar el trabajo anterior:** preservar la lógica de reflejo/brillo de tarjetas, corregir la configuración para admitir todas las apps cuando el límite sea cero, actualizar pruebas y conservar los respaldos útiles. Validar con tests y CI.
2. **Actualizar la especificación vigente:** integrar los requisitos nuevos sin borrar los anexos de firmware, ADB y recuperación de `MASTER_PROMPT.md`.
3. **Pantalla principal:** cinco apps recientes en una sola fila horizontal; panel independiente «CONTINUAR VIENDO» solo con metadatos reales verificables; controles «ENTRADAS», «WI-FI», «AJUSTES» y «BUSCAR»; botón inferior «CAJA DE APLICACIONES».
4. **Biblioteca de apps:** pantalla separada con carrusel 3D tipo Xbox 360, navegación por D-pad, iconos intactos y reflejos ligeros; sin panel de reproducción superpuesto.
5. **Fondos:** rotación aleatoria cada 10 segundos, fundido de 600 ms, movimiento sutil de 40 segundos y fallback local. Investigar selector/fuente de Google Photos sin asumir que existe una API accesible.
6. **Compatibilidad de «Continuar viendo»:** detectar solo datos públicos/permitidos y verificables; clasificar compatibilidad y ocultar el panel si no hay información real.
7. **Validación:** ejecutar `bash tools/verify.sh`, `bash tools/run_tests.sh`, compilación Gradle/CI, revisar el APK y actualizar `docs/TEST_REPORT.md` con evidencia. Las pruebas físicas del televisor permanecen `NOT TESTED` hasta disponer de una conexión ADB válida y ejecutar la prueba de forma segura.


## Requisitos visuales confirmados por el usuario (2026-10-26)

### Pantalla principal
- Las cinco aplicaciones recientes se muestran en una única fila horizontal; no añadir más tarjetas a esa fila.
- Las tarjetas tienen acabado de cristal (glassmorphism) con reflejo discreto.
- Al seleccionar una aplicación, debe aparecer un efecto de iluminación/glow que tome el color dominante/adaptativo del icono o de la aplicación. El efecto debe ser rápido y ligero; calcular/cachear el color al descubrir o cargar la app, no en cada evento de foco.
- El panel «CONTINUAR VIENDO» es una etiqueta/tarjeta muy pequeña, compacta y separada, colocada encima de la tarjeta de la aplicación compatible. El texto «CONTINUAR VIENDO» va dentro del mismo recuadro para no crear una sección alta ni ocupar espacio adicional.
- Solo mostrar ese recuadro si existe título y progreso/estado de reproducción real que pueda verificarse mediante una interfaz legítima. No inventar contenido y ocultarlo cuando no haya datos fiables.
- El acceso «CAJÓN DE APLICACIONES» se sitúa debajo de la fila de cinco apps recientes. Pulsar flecha abajo desde la fila reciente debe llevar el foco directamente al cajón/botón de aplicaciones; confirmar/OK abre la biblioteca.

### Biblioteca/cajón de aplicaciones
- Es una pantalla separada y se inspira en el carrusel 3D de Xbox 360 indicado en los requisitos anteriores: portada central destacada, laterales escalonados en tamaño y perspectiva, reflejos suaves, marco de cristal oscuro y resplandor adaptativo por color.
- La biblioteca conserva los iconos/logos originales y navega con el D-pad. No convertirla en una cuadrícula Metro ni en una lista vertical.
- No mostrar «CONTINUAR VIENDO» encima de las portadas de la biblioteca.

### Criterios de aceptación de interfaz
1. Exactamente cinco apps recientes visibles en la fila principal.
2. Flecha abajo desde esa fila enfoca el acceso al cajón; OK entra en la biblioteca.
3. El foco produce un glow de color adaptativo y un acabado de cristal sin ralentizar el D-pad.
4. «CONTINUAR VIENDO» cabe dentro de un único recuadro pequeño encima de la app correspondiente y desaparece si los metadatos no son verificables.
5. La biblioteca tiene navegación D-pad y efecto 3D tipo Xbox 360, sin paneles de reproducción superpuestos.
6. Las pruebas automatizadas cubren el límite de cinco recientes y la navegación entre fila reciente y cajón cuando la arquitectura de pruebas lo permita.

## Límites que no cambian

- No modificar el repositorio original `/storage/emulated/0/mi_launcher_tv`.
- No cambiar HOME predeterminado, reiniciar el televisor, instalar APK ni modificar ajustes globales sin autorización específica.
- No deshabilitar paquetes ni hacer debloating.
- No afirmar que algo está instalado, probado en TV o verificado sin evidencia.
- No añadir tokens, contraseñas ni claves a archivos o logs.
- Mantener `MASTER_PROMPT.md` y la documentación específica del dispositivo; los cambios se hacen incrementalmente y en esta rama.
