package com.temvra.app;

import android.content.Intent;
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

    @Override
    public void onResume() {
        super.onResume();
        // Con el móvil desbloqueado Android no abre la pantalla de alarma: dentro de la app no habría cómo pararla
        String alert = AlarmService.ringingAlert();
        if (alert != null) startActivity(new Intent(this, AlarmActivity.class).putExtra("alert", alert).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
