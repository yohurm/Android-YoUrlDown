package com.yohu.urldown;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.yohu.urldown.databinding.ActivityMainBinding;

/**
 * 首页骨架。
 *
 * <p>当前只做两件事：显示标题/说明，并在点击「解析」时提示"引擎尚未接入"。
 * 解析能力会先落到 {@code lib_urlparse}，再由此处调用——之所以先立骨架，
 * 是为了让后续迁移的每一批代码都有确定的落点与可编译的目标。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.actionParse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = binding.input.getText() == null
                        ? "" : binding.input.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请先粘贴链接或分享文本",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                // 解析引擎尚未接入 lib_urlparse，这里如实提示而不是伪造结果。
                binding.output.setText("输入长度: " + input.length()
                        + "\n解析引擎尚未接入（lib_urlparse 目前是空模块）。");
            }
        });
    }
}
