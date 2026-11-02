package com.temvra.app;

import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugin propio: música, notificación del temporizador y widgets
        registerPlugin(TemvraPlugin.class);
        super.onCreate(savedInstanceState);
        // La web se dibuja de borde a borde (detrás de la barra de estado y la de navegación).
        // Va después de super.onCreate: antes crearía la ventana con el tema de arranque, que tiene barra de acción
        EdgeToEdge.enable(this);
    }
}
