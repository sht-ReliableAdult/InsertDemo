package com.example.myplugin;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.utils.FileUtils;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tools.ant.taskdefs.Local;
import org.apache.tools.ant.taskdefs.Mkdir;
import org.apache.tools.ant.taskdefs.SubAnt;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/**
 * 继承Gradle安卓版插件提供的Transform
 */
public class InsertTransform extends Transform {
    /**
     * 给本Transform取个名字
     * @return
     */
    @Override
    public String getName() {
        return "myTrans";
    }

    /**
     * 设定TransForm要接收的内容，有class和resource两种
     * @return
     */
    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    /**
     * 设置作用域，是只拿自己的App的class还是包括远程依赖的class那些
     * @return
     */
    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    /**
     * 定义Transform要对取到的东西干什么
     * @param transformInvocation
     * @throws TransformException
     * @throws InterruptedException
     * @throws IOException
     */

    @Override
    public void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation);
        //获取操作空间提供者,并先清空,避免还有其他垃圾
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        outputProvider.deleteAll();
        //获取所有输入，即所有的class目录与所有的jar包目录
        Collection<TransformInput> inputs = transformInvocation.getInputs();
        for (TransformInput input : inputs) {
            //获取jar包class的目录
            Collection<JarInput> jarInputs = input.getJarInputs();
            for (JarInput jarInput : jarInputs) {
                //规定transform得到的不处理的class要复制到另一个目录下去
                String dirName = jarInput.getName();
                File src = jarInput.getFile();
                //加密生成一个唯一标识符
                String md5Name = DigestUtils.md5Hex(src.getAbsolutePath());
                //provider帮忙找中转地方
                File dest = outputProvider.getContentLocation(dirName + md5Name, jarInput.getContentTypes()
                        ,jarInput.getScopes(), Format.JAR);
                FileUtils.copyFile(src, dest);
            }

            //获取自己的class的目录

            Collection<DirectoryInput> directoryInputs = input.getDirectoryInputs();
            for (DirectoryInput directoryInput : directoryInputs) {
                String name = directoryInput.getName();
                File file = directoryInput.getFile();
                //DFS获取目录下的所有class文件
                List<File> classFiles = getClassFormDirectory(file, new ArrayList<File>());
                //生成一段加密标识符，可以与之前的比对看类是否调整过
                String md5Name = DigestUtils.md5Hex(file.getAbsolutePath());
                //获取provider提供的对应中转目录
                File fileOutputDirectory = outputProvider.getContentLocation(name + md5Name, directoryInput.getContentTypes()
                        ,directoryInput.getScopes(), Format.DIRECTORY);
                System.out.println(fileOutputDirectory);
                for (File classFile : classFiles) {
                    //获取规范路径名
                    String classFilePath = classFile.getCanonicalPath();
                    String classFileName = "";
                    System.out.println(classFilePath);
                    //验证确实是来自对应inputDirectory下的class，才处理
                    if (classFilePath.startsWith(file.getCanonicalPath())) {
                        classFileName = classFilePath.substring(file.getCanonicalPath().length() + 1);
                    }
                    //生成要输出到中转目录下的完整class绝对路径
                    File outPutClassFile = new File(fileOutputDirectory, classFileName);
                    //递归创建目录
                    if (outPutClassFile.getParentFile() != null) {
                        outPutClassFile.getParentFile().mkdirs();
                    }
                    System.out.println(outPutClassFile);
                    doInsert(classFile, outPutClassFile);
                }
            }
        }
    }

    private List<File> getClassFormDirectory(File dirFile, ArrayList<File> list) {
        if (dirFile.exists()) {
            File[] files = dirFile.listFiles();
            if (files != null) {
                for (File fileChildDir : files) {
                    if (fileChildDir.isDirectory()) {
                        getClassFormDirectory(fileChildDir, list);
                    }
                    if (fileChildDir.isFile()) {
                        System.out.println(fileChildDir);
                        list.add(fileChildDir);
                    }
                }
            }
        } else {
            System.out.println("你想查找的文件不存在");
        }
        return list;
    }

    /**
     * 插桩逻辑
     */
    private void doInsert(File classFile, File fileOutput) throws IOException {
        if (classFile.getName().contains("Fragment") || classFile.getName().contains("Application")) {
            FileUtils.copyFile(classFile, fileOutput);
        }else {
            FileInputStream inputStream = new FileInputStream(classFile);
            //这里可以传byte数组或者IOStream
            ClassReader cr = new ClassReader(inputStream);
            //代表class自动计算栈帧和局部变量表大小，其他模式需要自己计算
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            //执行分析修改,需要传一个传版本号和ClassWriter的ClassVisitor和另一个参数
            cr.accept(new MyClassVisitor(Opcodes.ASM5, cw), ClassReader.EXPAND_FRAMES);
            //将修改结果转成byte数组覆盖原class
            byte[] resultClass = cw.toByteArray();
            FileOutputStream outputStream = new FileOutputStream(fileOutput);
            outputStream.write(resultClass);
            inputStream.close();
            outputStream.close();
        }

    }

    static class MyClassVisitor extends ClassVisitor {

        public MyClassVisitor(int i, ClassVisitor classVisitor) {

            super(i, classVisitor);
        }
        //访问到某一个类的方法，只能处理方法声明，要处理方法体需要MethodVisitor
        @Override
        public MethodVisitor visitMethod(int i, String s, String s1, String s2, String[] strings) {
            System.out.println(s);
            //如果是打印日志方法，就返回带有修改逻辑的MethodVisitor，其他方法返回默认什么也不改的MethodVisitor
            MethodVisitor methodVisitor = super.visitMethod(i, s, s1, s2, strings);
            if (s.equals("exportLogInfo")) {
                return new MyMethodVisitor(this.api, methodVisitor, i, s, s1);
            }
            return methodVisitor;
        }

        /**
         * MethodVisitor真正完成插桩
         */
        static class MyMethodVisitor extends AdviceAdapter {

            protected MyMethodVisitor(int i, org.objectweb.asm.MethodVisitor methodVisitor, int i1, String s, String s1) {
                super(i, methodVisitor, i1, s, s1);
            }
            //刚进入方法
            @Override
            protected void onMethodEnter() {
                super.onMethodEnter();
                //插入Lang l = System.currentTimeMillis();
                /*
                //1.执行System.currentTimeMillis()
                invokeStatic(Type.getType("Ljava/lang/System;"),new Method("currentTimeMills", "()J"));
                //2.1创建一个Long类型本地变量，在本处代号为s
                s = newLocal(Type.LONG_TYPE);
                //2.2用代号s的本地变量接收执行结果
                storeLocal(s);
                 */
            }
            int s;
            //马上退出方法
            @Override
            public void monitorExit() {
                super.monitorExit();
                //插入long e = System.currentTimeMillis();
                //System.out.println("excute" + (e - l) + "ms.");
                /*
                invokeStatic(Type.getType("Ljava/lang/System;"),new Method("currentTimeMills", "()J"));
                int e = newLocal(Type.LONG_TYPE);
                storeLocal(e);
                //执行sout静态反法
                getStatic(Type.getType("Ljava/lang/System;"), "out", Type.getType("Ljava/io/PrintStream;"));
                //new StringBuilder
                newInstance(Type.getType("Ljava/lang/StringBuilder;"));
                dup();
                //执行SB构造方法
                invokeConstructor(Type.getType("Ljava/long/StringBuilder;"),new Method("<init>", "()V"));
                //添加字符串excute
                visitLdcInsn("excute:");
                //执行SB的append方法
                invokeVirtual(Type.getType("Ljava/long/StringBuilder;"),new Method("append", "(Ljava/long/String;)Ljava/long/StringBuilder;"));
                //两个时间作减法
                loadLocal(e);
                loadLocal(s);
                math(SUB, Type.LONG_TYPE);
                //把Long类型的差append进去
                invokeVirtual(Type.getType("Ljava/long/StringBuilder;"),new Method("append", "(J)Ljava/long/StringBuilder;"));
                //添加字符串ms.
                visitLdcInsn("ms.");
                invokeVirtual(Type.getType("Ljava/long/StringBuilder;"),new Method("append", "(Ljava/long/String;)Ljava/long/StringBuilder;"));
                invokeVirtual(Type.getType("Ljava/long/StringBuilder;"),new Method("toString", "()Ljava/long/String;"));
                invokeVirtual(Type.getType("Ljava/io/PrintStream;"),new Method("println", "(Ljava/long/String;)V"));
                 */
            }
            //这里可以实现删除指定方法内的某方法
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                //走到指定方法，不还原直接return
                if (name.equals("d")) {
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }
    }

    @Override
    public boolean isIncremental() {
        return false;
    }
}
