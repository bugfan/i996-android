package com.example.app;

import androidx.annotation.ColorInt;
import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

public class MainActivity extends AppCompatActivity {
    Boolean running=false;
    Button btn;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        start();
    }
    private void start(){
        btn = (Button) findViewById(R.id.button);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                run();
            }
        });
    }
    private void setBtnText(Boolean state){
        if (state){
            btn.setText("点击停止");
        }else{
            btn.setText("点击启动");
        }
    }
    private void setBtnColor(Boolean state){
        if (state){
            btn.setBackgroundColor(Color.GREEN);
        }else{
            btn.setBackgroundColor(Color.GRAY);
        }

    }
    private  void run(){
        if(running){
            running=false;
        }else{
            running=true;
        }
        setBtnText(running);
        setBtnColor(running);
    }
}