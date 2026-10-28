package com.example.arduinobeta

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private data class SolicitudDispensado(
        val gramosObjetivo: Int,
        val automatica: Boolean,
        val completado: CompletableDeferred<Unit>? = null
    )

    private lateinit var arduinoRef: DatabaseReference
    private lateinit var dispensadorRef: DatabaseReference

    // El temporizador y el procesador de órdenes funcionan independientemente.
    private var relojJob: Job? = null
    private var procesadorJob: Job? = null

    // Las solicitudes se procesan de una en una.
    private val colaDispensado = Channel<SolicitudDispensado>(Channel.UNLIMITED)

    private var tiempoEspera = 10
    private var bloqueoActivo = false
    private var gramosObjetivo = 0

    // Valor del simulador, controlado por el CheckBox.
    private var comidaDisponible = true
    private var ultimoEstadoComidaPublicado: Boolean? = null

    // Gramos que ya hay en el plato. Se recuperan de Firebase al iniciar.
    private var gramosActualesEnPlato = 0
    private val gramosInicialesCargados = CompletableDeferred<Unit>()
    private var dispensadoEnCurso = false

    // Evita encolar varias veces una misma orden manual.
    private var ordenManualYaEncolada = false

    private fun actualizarTextoComida() {
        val txtComidaCuenta = findViewById<TextView>(R.id.comida_cuenta)
        txtComidaCuenta.text = "$gramosActualesEnPlato/$gramosObjetivo"
    }

    /** Publica el estado del CheckBox en arduino/comida_disponible. */
    private fun actualizarComidaDisponible(disponible: Boolean) {
        comidaDisponible = disponible

        if (ultimoEstadoComidaPublicado == disponible) return
        ultimoEstadoComidaPublicado = disponible

        arduinoRef.child("comida_disponible")
            .setValue(disponible)
            .addOnFailureListener { error ->
                ultimoEstadoComidaPublicado = null
                Log.e(
                    "AppArduino",
                    "Error al publicar disponibilidad de comida",
                    error
                )
            }

        Log.d("AppArduino", "Comida disponible: $disponible")
    }

    /**
     * Escucha gramos_actual para reflejar cambios en pantalla.
     * Este listener NO crea órdenes de dispensado.
     */
    private fun escucharGramosActuales() {
        arduinoRef.child("gramos_actual")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val nuevoValor = maxOf(
                        0,
                        snapshot.getValue(Int::class.javaObjectType) ?: 0
                    )
                    gramosActualesEnPlato = nuevoValor
                    actualizarTextoComida()
                    gramosInicialesCargados.complete(Unit)

                    Log.d("AppArduino", "Gramos actuales en el plato: $gramosActualesEnPlato")

                    // Importante: cambiar los gramos del plato solo sincroniza el estado.
                    // No debe iniciar un dispensado; eso requiere una orden manual o el timer.
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "AppArduino",
                        "No se pudieron leer los gramos actuales",
                        error.toException()
                    )
                    gramosInicialesCargados.complete(Unit)
                }
            })
    }

    /**
     * Cuenta atrás automática. Las órdenes manuales no la reinician ni la pausan.
     * Al llegar a cero, encola una porción automática y espera a que se complete.
     */
    private fun iniciarTemporizadorAutomatico() {
        if (relojJob?.isActive == true) return

        relojJob = lifecycleScope.launch {
            while (isActive) {
                // La nueva duración se toma al comenzar cada ciclo.
                val duracionDelCiclo = tiempoEspera

                if (duracionDelCiclo <= 0) {
                    arduinoRef.child("tiempo_restante").setValue(0)
                    delay(500)
                    continue
                }

                for (segundos in duracionDelCiclo downTo 1) {
                    arduinoRef.child("tiempo_restante").setValue(segundos)
                    delay(1000)
                }

                arduinoRef.child("tiempo_restante").setValue(0)

                val porcionAutomaticaTerminada = CompletableDeferred<Unit>()
                colaDispensado.send(
                    SolicitudDispensado(
                        gramosObjetivo = gramosObjetivo,
                        automatica = true,
                        completado = porcionAutomaticaTerminada
                    )
                )

                // Una porción automática debe terminar antes de que empiece
                // el siguiente ciclo automático. Una orden manual no modifica
                // el conteo; el reloj sigue durante la dispensación manual.
                porcionAutomaticaTerminada.await()
            }
        }
    }

    private fun iniciarProcesadorDeOrdenes() {
        if (procesadorJob?.isActive == true) return

        procesadorJob = lifecycleScope.launch {
            for (solicitud in colaDispensado) {
                try {
                    val txtDispensador =
                        findViewById<TextView>(R.id.mensaje_dispensador)

                    // Una orden queda en espera mientras el bloqueo esté activo
                    // o el CheckBox indique que no hay comida disponible.
                    while (isActive && (bloqueoActivo || !comidaDisponible)) {
                        txtDispensador.text = when {
                            bloqueoActivo -> "Dispensador bloqueado"
                            else -> "Sin comida disponible"
                        }
                        delay(300)
                    }

                    if (!isActive) break

                    if (solicitud.gramosObjetivo <= 0) {
                        txtDispensador.text = "Objetivo de gramos inválido"
                        Log.e(
                            "AppArduino",
                            "Objetivo inválido: ${solicitud.gramosObjetivo}"
                        )
                    } else {
                        val origen = if (solicitud.automatica) "automática" else "manual"
                        Log.d(
                            "AppArduino",
                            "Procesando orden $origen; objetivo=${solicitud.gramosObjetivo}, " +
                                    "actual=$gramosActualesEnPlato"
                        )
                        dispensarPorcion(solicitud.gramosObjetivo)
                    }
                } finally {
                    solicitud.completado?.complete(Unit)
                }
            }
        }
    }

    /** Espera si se activa el bloqueo o se marca que no hay comida. */
    private suspend fun esperarPermisoParaDispensar(txtDispensador: TextView) {
        while (bloqueoActivo || !comidaDisponible) {
            txtDispensador.text = when {
                bloqueoActivo -> "Dispensador bloqueado"
                else -> "Sin comida disponible"
            }
            delay(300)
        }
    }

    /**
     * Dispensar hasta alcanzar el objetivo absoluto del plato.
     * Ejemplo: si ya hay 155 g y el objetivo es 200 g, empieza en 155 g
     * y añade únicamente los 45 g restantes.
     */
    private suspend fun dispensarPorcion(gramosObjetivoPorcion: Int) {
        gramosInicialesCargados.await()

        if (gramosObjetivoPorcion <= 0) return

        val txtDispensador = findViewById<TextView>(R.id.mensaje_dispensador)
        val txtComidaCuenta = findViewById<TextView>(R.id.comida_cuenta)
        val gramosPorVuelta = 20

        actualizarTextoComida()

        // Si el plato ya alcanzó o superó el objetivo, no volver a dispensar.
        if (gramosActualesEnPlato >= gramosObjetivoPorcion) {
            txtDispensador.text = "Objetivo ya alcanzado"
            arduinoRef.child("dispensado_comida").setValue(false)
            arduinoRef.child("comida_dispensada_lista").setValue(true)
            Log.d(
                "AppArduino",
                "No se dispensa: ya hay $gramosActualesEnPlato g, objetivo $gramosObjetivoPorcion g"
            )
            return
        }

        dispensadoEnCurso = true
        arduinoRef.child("comida_dispensada_lista").setValue(false)
        arduinoRef.child("dispensado_comida").setValue(true)

        try {
            while (gramosActualesEnPlato < gramosObjetivoPorcion) {
                // Bloqueo/disponibilidad se comprueban durante toda la porción.
                esperarPermisoParaDispensar(txtDispensador)

                txtDispensador.text = "Dispensando."
                delay(300)
                txtDispensador.text = "Dispensando.."
                delay(300)
                txtDispensador.text = "Dispensando..."
                delay(300)

                esperarPermisoParaDispensar(txtDispensador)

                val gramosRestantes = gramosObjetivoPorcion - gramosActualesEnPlato
                val incrementoReal = minOf(gramosPorVuelta, gramosRestantes)
                gramosActualesEnPlato += incrementoReal

                txtComidaCuenta.text =
                    "$gramosActualesEnPlato/$gramosObjetivoPorcion"

                arduinoRef.child("gramos_actual")
                    .setValue(gramosActualesEnPlato)
                    .addOnFailureListener { error ->
                        Log.e("AppArduino", "Error al guardar gramos actuales", error)
                    }

                Log.d(
                    "AppArduino",
                    "Gramos en el plato: $gramosActualesEnPlato/$gramosObjetivoPorcion"
                )
            }

            txtDispensador.text = "LISTO"
            Log.d("AppArduino", "Objetivo alcanzado: $gramosActualesEnPlato g")
        } finally {
            arduinoRef.child("dispensado_comida").setValue(false)
            arduinoRef.child("comida_dispensada_lista").setValue(true)
            dispensadoEnCurso = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val database = FirebaseDatabase.getInstance(
            "https://prueba-2-e60c5-default-rtdb.firebaseio.com/"
        )

        dispensadorRef = database.getReference("dispensador")
        arduinoRef = database.getReference("arduino")

        // Escuchar los gramos del plato. Esto también detecta cambios hechos directamente en Firebase.
        escucharGramosActuales()

        // El CheckBox controla el estado simulado del sensor de comida.
        val checkComidaDisponible =
            findViewById<CheckBox>(R.id.check_comida_disponible)
        checkComidaDisponible.isChecked = comidaDisponible
        checkComidaDisponible.setOnCheckedChangeListener { _, checked ->
            actualizarComidaDisponible(checked)
        }
        actualizarComidaDisponible(checkComidaDisponible.isChecked)

        // Control manual para ajustar los gramos del plato SIN dispensar.
        val inputGramosPlato = findViewById<EditText>(R.id.input_gramos_plato)
        val btnActualizarPlato = findViewById<Button>(R.id.btn_actualizar_plato)
        btnActualizarPlato.setOnClickListener {
            val texto = inputGramosPlato.text.toString().trim()
            val nuevoValor = texto.toIntOrNull()

            if (nuevoValor == null || nuevoValor < 0) {
                findViewById<TextView>(R.id.mensaje_dispensador).text =
                    "Ingresa una cantidad válida (0 o más)"
                return@setOnClickListener
            }

            if (dispensadoEnCurso) {
                findViewById<TextView>(R.id.mensaje_dispensador).text =
                    "Espera a que termine la porción para ajustar el plato"
                return@setOnClickListener
            }

            gramosActualesEnPlato = nuevoValor
            actualizarTextoComida()

            arduinoRef.child("gramos_actual")
                .setValue(nuevoValor)
                .addOnSuccessListener {
                    inputGramosPlato.text.clear()
                    Log.d("AppArduino", "Plato ajustado manualmente a $nuevoValor g")

                    findViewById<TextView>(R.id.mensaje_dispensador).text =
                        "Plato actualizado. Sin iniciar dispensado."
                }
                .addOnFailureListener { error ->
                    Log.e("AppArduino", "No se pudo actualizar el plato", error)
                    findViewById<TextView>(R.id.mensaje_dispensador).text =
                        "Error al actualizar el plato"
                }
        }

        iniciarProcesadorDeOrdenes()

        // Estado de conexión del simulador.
        val estadoRef = database.getReference("dispensador/estado_simulador")
        val conexionRef = database.getReference(".info/connected")

        conexionRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val conectado = snapshot.getValue(Boolean::class.java) ?: false

                if (conectado) {
                    estadoRef.onDisconnect().setValue("desconectado") { error, _ ->
                        if (error == null) {
                            estadoRef.setValue("conectado")
                        } else {
                            Log.e(
                                "AppArduino",
                                "No se pudo configurar el estado de desconexión",
                                error.toException()
                            )
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("AppArduino", "Error de conexión", error.toException())
            }
        })

        // Leer órdenes y configuración de la aplicación principal.
        dispensadorRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                bloqueoActivo = snapshot.child("bloqueo_activo")
                    .getValue(Boolean::class.javaObjectType) ?: false

                gramosObjetivo = snapshot.child("gramos_objetivo")
                    .getValue(Int::class.javaObjectType) ?: 0

                tiempoEspera = snapshot.child("tiempo_restante_seg")
                    .getValue(Int::class.javaObjectType) ?: 10

                val ordenManual = snapshot.child("dispensar_ahora")
                    .getValue(Boolean::class.javaObjectType) ?: false

                Log.d("AppArduino", "Bloqueo activo: $bloqueoActivo")
                Log.d("AppArduino", "Gramos objetivo: $gramosObjetivo")
                Log.d("AppArduino", "Duración configurada: $tiempoEspera")
                Log.d("AppArduino", "Orden manual recibida: $ordenManual")

                actualizarTextoComida()

                // Detectar false -> true para encolar la orden manual una vez.
                if (ordenManual && !ordenManualYaEncolada) {
                    ordenManualYaEncolada = true

                    val resultado = colaDispensado.trySend(
                        SolicitudDispensado(
                            gramosObjetivo = gramosObjetivo,
                            automatica = false
                        )
                    )

                    if (resultado.isSuccess) {
                        dispensadorRef.child("dispensar_ahora").setValue(false)
                    } else {
                        ordenManualYaEncolada = false
                        Log.e("AppArduino", "No se pudo encolar la orden manual")
                    }
                } else if (!ordenManual) {
                    ordenManualYaEncolada = false
                }

                // Iniciar el reloj una sola vez; cambios del listener no lo reinician.
                if (relojJob?.isActive != true) {
                    iniciarTemporizadorAutomatico()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(
                    "AppArduino",
                    "Error al leer Firebase",
                    error.toException()
                )
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
    }
}
