package com.example.myplugin;

import com.android.build.gradle.AppExtension;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionContainer;

public class InsertPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        ExtensionContainer extensions = project.getExtensions();//获取项目所有扩展
        AppExtension appExtension = (AppExtension) extensions.findByName("android");//找到Gradle安卓版插件扩展
        appExtension.registerTransform(new InsertTransform());//注册一个transform，
        /*
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                System.out.println("success----------------------");
            }
        });
         */
    }
}