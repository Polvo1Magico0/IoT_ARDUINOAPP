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

    // El reloj y el procesador de órdenes funcionan por separado.
    private var relojJob: Job? = null
    private var procesadorJob: Job? = null

    // Todas las porciones se procesan en orden, una a la vez.
    private val colaDispensado = Channel<SolicitudDispensado>(Channel.UNLIMITED)

    private var tiempoEspera = 10
    private var bloqueoActivo = false
    private var gramosObjetivo = 0

    // Estado simulado del sensor, controlado por el CheckBox.
    private var comidaDisponible = true
    private var ultimoEstadoComidaPublicado: Boolean? = null

    // Cantidad actual en el plato, leída y guardada en Firebase.
    private var gramosActualesEnPlato = 0
    private val gramosInicialesCargados = CompletableDeferred<Unit>()
    private var dispensadoEnCurso = false

    // Evita encolar repetidamente una misma orden manual.
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

    /** Escucha los gramos del plato y actualiza la interfaz. No genera órdenes. */
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

                    Log.d(
                        "AppArduino",
                        "Gramos actuales en el plato: $gramosActualesEnPlato"
                    )
                    // Modificar los gramos solo sincroniza el estado. No dispensa.
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
     * Ejecuta ciclos automáticos. Cada ciclo hace la cuenta atrás, encola una sola
     * orden automática al llegar a cero y espera a que esa orden se procese antes
     * de comenzar el siguiente ciclo. Las órdenes manuales no reinician ni pausan
     * la cuenta atrás.
     */
    private fun iniciarTemporizadorAutomatico() {
        if (relojJob?.isActive == true) return

        relojJob = lifecycleScope.launch {
            while (isActive) {
                // La duración se fija al inicio de cada ciclo.
                val duracionDelCiclo = tiempoEspera

                if (duracionDelCiclo <= 0) {
                    arduinoRef.child("tiempo_restante").setValue(0)
                    Log.w(
                        "AppArduino",
                        "Duración de temporizador inválida: $duracionDelCiclo. " +
                                "Se volverá a revisar en 1 segundo."
                    )
                    delay(1000)
                    continue
                }

                // Cuenta regresiva real: actualiza Firebase cada segundo.
                for (segundos in duracionDelCiclo downTo 1) {
                    if (!isActive) break

                    arduinoRef.child("tiempo_restante")
                        .setValue(segundos)
                        .addOnFailureListener { error ->
                            Log.e(
                                "AppArduino",
                                "Error al actualizar el tiempo restante",
                                error
                            )
                        }

                    delay(1000)
                }

                if (!isActive) break

                // El ciclo llegó a cero. Publicarlo y crear UNA orden automática.
                arduinoRef.child("tiempo_restante").setValue(0)
                Log.d("AppArduino", "Temporizador terminado; encolando orden automática")

                val porcionAutomaticaTerminada = CompletableDeferred<Unit>()
                val solicitudAutomatica = SolicitudDispensado(
                    gramosObjetivo = gramosObjetivo,
                    automatica = true,
                    completado = porcionAutomaticaTerminada
                )

                val resultado = colaDispensado.trySend(solicitudAutomatica)
                if (resultado.isSuccess) {
                    // Espera a que termine esta orden antes de iniciar el siguiente ciclo.
                    // Si una orden manual está delante en la cola, se atiende primero.
                    porcionAutomaticaTerminada.await()
                    Log.d(
                        "AppArduino",
                        "Ciclo automático completado; comenzará la siguiente cuenta atrás"
                    )
                } else {
                    Log.e(
                        "AppArduino",
                        "No se pudo encolar la orden automática",
                        resultado.exceptionOrNull()
                    )
                    delay(1000)
                }
            }
        }
    }

    /** Consume la cola de solicitudes secuencialmente. */
    private fun iniciarProcesadorDeOrdenes() {
        if (procesadorJob?.isActive == true) return

        procesadorJob = lifecycleScope.launch {
            for (solicitud in colaDispensado) {
                try {
                    val txtDispensador =
                        findViewById<TextView>(R.id.mensaje_dispensador)

                    // Esperar mientras el dispensador esté bloqueado o no haya comida.
                    while (isActive && (bloqueoActivo || !comidaDisponible)) {
                        txtDispensador.text = when {
                            bloqueoActivo -> "Dispensador bloqueado"
                            else -> "Sin comida disponible"
                        }
                        delay(300)
                    }

                    if (!isActive) break

                    val objetivoDeEstaOrden = solicitud.gramosObjetivo
                    when {
                        objetivoDeEstaOrden <= 0 -> {
                            txtDispensador.text = "Objetivo de gramos inválido"
                            Log.e(
                                "AppArduino",
                                "Objetivo inválido: $objetivoDeEstaOrden"
                            )
                        }

                        gramosActualesEnPlato >= objetivoDeEstaOrden -> {
                            // No dispensar si ya se alcanzó el objetivo.
                            txtDispensador.text = "Objetivo de comida alcanzado"
                            Log.d(
                                "AppArduino",
                                "Orden ${if (solicitud.automatica) "automática" else "manual"} " +
                                        "ignorada: hay $gramosActualesEnPlato g, " +
                                        "objetivo $objetivoDeEstaOrden g"
                            )
                        }

                        else -> {
                            val origen = if (solicitud.automatica) "automática" else "manual"
                            Log.d(
                                "AppArduino",
                                "Procesando orden $origen; objetivo=$objetivoDeEstaOrden, " +
                                        "actual=$gramosActualesEnPlato"
                            )
                            dispensarPorcion(objetivoDeEstaOrden)
                        }
                    }
                } catch (error: Exception) {
                    Log.e("AppArduino", "Error al procesar una orden", error)
                    findViewById<TextView>(R.id.mensaje_dispensador).text =
                        "Error al procesar orden"
                } finally {
                    // El reloj automático puede continuar después de que esta orden finalice.
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

    /** Dispensa hasta el objetivo absoluto, usando los gramos que ya hay en el plato. */
    private suspend fun dispensarPorcion(gramosObjetivoPorcion: Int) {
        gramosInicialesCargados.await()
        if (gramosObjetivoPorcion <= 0) return

        val txtDispensador = findViewById<TextView>(R.id.mensaje_dispensador)
        val txtComidaCuenta = findViewById<TextView>(R.id.comida_cuenta)
        val gramosPorVuelta = 20

        actualizarTextoComida()

        if (gramosActualesEnPlato >= gramosObjetivoPorcion) {
            txtDispensador.text = "Objetivo ya alcanzado"
            arduinoRef.child("dispensado_comida").setValue(false)
            arduinoRef.child("comida_dispensada_lista").setValue(true)
            Log.d(
                "AppArduino",
                "No se dispensa: ya hay $gramosActualesEnPlato g, " +
                        "objetivo $gramosObjetivoPorcion g"
            )
            return
        }

        dispensadoEnCurso = true
        arduinoRef.child("comida_dispensada_lista").setValue(false)
        arduinoRef.child("dispensado_comida").setValue(true)

        try {
            while (gramosActualesEnPlato < gramosObjetivoPorcion) {
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

                txtComidaCuenta.text = "$gramosActualesEnPlato/$gramosObjetivoPorcion"

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

        escucharGramosActuales()

        // El CheckBox representa el sensor de comida del simulador.
        val checkComidaDisponible =
            findViewById<CheckBox>(R.id.check_comida_disponible)
        checkComidaDisponible.isChecked = comidaDisponible
        checkComidaDisponible.setOnCheckedChangeListener { _, checked ->
            actualizarComidaDisponible(checked)
        }
        actualizarComidaDisponible(checkComidaDisponible.isChecked)

        // Permite ajustar el peso del plato sin generar ninguna orden.
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

        // Publicar el estado de conexión del simulador.
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

        // Leer las órdenes y la configuración de la app principal.
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

                // Encolar la orden manual una sola vez cuando cambia de false a true.
                if (ordenManual && !ordenManualYaEncolada) {
                    ordenManualYaEncolada = true

                    val resultado = colaDispensado.trySend(
                        SolicitudDispensado(
                            gramosObjetivo = gramosObjetivo,
                            automatica = false
                        )
                    )

                    if (resultado.isSuccess) {
                        // Consumir la señal; el procesador ya tiene la solicitud en la cola.
                        dispensadorRef.child("dispensar_ahora").setValue(false)
                    } else {
                        ordenManualYaEncolada = false
                        Log.e(
                            "AppArduino",
                            "No se pudo encolar la orden manual",
                            resultado.exceptionOrNull()
                        )
                    }
                } else if (!ordenManual) {
                    ordenManualYaEncolada = false
                }

                // El temporizador se inicia una sola vez. Cambios de Firebase no lo reinician.
                if (relojJob?.isActive != true) {
                    iniciarTemporizadorAutomatico()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("AppArduino", "Error al leer Firebase", error.toException())
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

    override fun onDestroy() {
        relojJob?.cancel()
        procesadorJob?.cancel()
        colaDispensado.close()
        super.onDestroy()
    }
}
