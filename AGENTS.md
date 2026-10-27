# Directrices de Trabajo y Automatización para Agentes

Este archivo establece las reglas permanentes de operación en este repositorio para cualquier agente de IA.

## 1. Alcance y Límites de Seguridad
- **Área de trabajo exclusiva:** `/root/ai-workspace/mi_launcher_tv-copia`.
- **Repositorio original:** Queda estrictamente prohibido modificar `/sdcard/mi_launcher_tv` o cualquier archivo fuera de esta copia.
- **Integridad de archivos:** Conservar siempre `MASTER_PROMPT.md` y archivos existentes.
- **Televisor y ADB:**
  - Prohibido modificar configuraciones globales del televisor.
  - Prohibido cambiar el launcher HOME predeterminado en el televisor sin autorización explícita.
  - Prohibido deshabilitar o eliminar paquetes de sistema o debloating.
  - Prohibido eludir controles de permisos del entorno.
  - Toda consulta sobre el televisor debe ser de sólo lectura (`getprop`, `dumpsys`).

## 2. Ejecución de Scripts y Herramientas
- **No asumir `chmod +x`:** No intentar cambiar permisos de ejecución con `chmod`.
- **Invocación explícita:**
  - Scripts bash: ejecutar siempre mediante `bash tools/<script>.sh`
  - Scripts python: ejecutar siempre mediante `python3 tools/<script>.py`
- **Verificación previa:** Comprobar siempre la sintaxis y el estado de los archivos antes de confirmar cambios.

## 3. Flujo de Trabajo Automatizado
1. **Analizar:** Leer el estado actual y verificar los requerimientos de la fase.
2. **Implementar:** Escribir código limpio, modular y documentado.
3. **Probar:** Ejecutar las pruebas unitarias y de validación mediante `bash tools/run_tests.sh`.
4. **Registrar:** Actualizar `docs/TEST_REPORT.md` con los resultados reales obtenidos.
5. **Reportar:** Explicar claramente al usuario los archivos modificados, las pruebas ejecutadas, los resultados de compilación y los bloqueos existentes.
