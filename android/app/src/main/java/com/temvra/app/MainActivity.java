package com.temvra.app;

import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugin propio: música, notificación del temporizador y widgets
        registerPlugin(TemvraPlugin.class);
        // La web se dibuja de borde a borde (detrás de la barra de estado y la de navegación)
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }
}
