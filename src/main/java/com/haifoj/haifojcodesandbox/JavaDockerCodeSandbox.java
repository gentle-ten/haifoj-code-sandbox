package com.haifoj.haifojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.haifoj.haifojcodesandbox.model.ExecuteCodeRequest;
import com.haifoj.haifojcodesandbox.model.ExecuteCodeResponse;
import com.haifoj.haifojcodesandbox.model.ExecuteMessage;
import com.haifoj.haifojcodesandbox.model.JudgeInfo;
import com.haifoj.haifojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class JavaDockerCodeSandbox extends JavaNativeCodeSandboxTemplate {


    /**
     * 要执行的类名
     */
    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    /**
     * 时间限制
     */
    private static final Long TIME_OUT = 5000L;



    /**
     * 测试创建文件夹，获取用户输入的代码
     */
    public static void main(String[] args) {
        JavaDockerCodeSandbox javaNativeCodeSandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs" + File.separator + GLOBAL_JAVA_CLASS_NAME, StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafe/ReadFileError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/simpleCompute" + File.separator + GLOBAL_JAVA_CLASS_NAME, StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        executeCodeRequest.setInputList(Arrays.asList("1 2", "3 4"));
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println("executeCodeResponse = " + executeCodeResponse);
    }


    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        // 获取主机路径
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        // 获取默认的 docker client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        String image = "openjdk:8-alpine";
        // 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        // 配置容器的 主机配置
        HostConfig hostConfig = new HostConfig();
        // 使用 linux 内核机制，管理权限
        String profileConfig = ResourceUtil.readUtf8Str("/home/haif/haifoj-code-sandbox/src/main/resources/profile.json");
        hostConfig.withSecurityOpts(Collections.singletonList("seccomp=" + profileConfig));
        // 设置内存使用
        hostConfig.withMemory(0L);
        // 设置容器内存限制
        hostConfig.withMemory(100 * 1000 * 1000L);
        // 设置 cpu 核心数
        hostConfig.withCpuCount(1L);
        // 文件挂载：将主机上的文件或目录与容器内部的路径关联起来，以便容器可访问主机上的特定文件或目录
        // 参数一是：主机路径，参数二是：容器内部路径，通过 Bind 对象进行绑定
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));
        // 创建容器命令，并配置容器参数
        CreateContainerResponse createContainerResponse = containerCmd
                // 设置网络配置为关闭
                .withNetworkDisabled(true)
                // 限制用户不能向 root 根目录写文件
                .withReadonlyRootfs(true)
                // 设置主机配置
                .withHostConfig(hostConfig)
                // 将容器的标准错误输出连接到当前进程
                .withAttachStderr(true)
                // 将容器的标准输出连接到当前进程
                .withAttachStdout(true)
                // 启用标准输入连接，允许向容器发送输入
                .withAttachStdin(true)
                // 启用终端模式，通常用于交互式应用程序
                .withTty(true)
                // 执行容器创建命令，并将结果保存在 createContainerResponse 变量中
                .exec();
        System.out.println("容器参数：" + createContainerResponse);
        // 获取容器 id
        String containerResponseId = createContainerResponse.getId();
        // 启动容器
        dockerClient.startContainerCmd(containerResponseId).exec();
        System.out.println("容器启动成功！" + containerResponseId);

        // 3. 执行代码，得到输出结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java","-cp", "/app", "Main"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerResponseId)
                    // 命令数组
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.out.println("创建执行命令：" + execCreateCmdResponse);
            String createCmdResponseId = execCreateCmdResponse.getId();
            ExecuteMessage executeMessage = new ExecuteMessage();
            final boolean[] timeOut = {true};
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback(){
                /**
                 * 但这个方法被调用的时候，表示操作已经完成
                 */
                @Override
                public void onComplete() {
                    // 如果执行没超时，则改为false
                    timeOut[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType type = frame.getStreamType();
                    if (type.equals(StreamType.STDERR)) {
                        // 错误输出
                        String errorMessage = new String(frame.getPayload());
                        executeMessage.setErrorMessage(errorMessage);
                        executeMessage.setExitValue(1);
                        System.out.println("输出错误结果：" + errorMessage);
                    }else {
                        // 正确输出
                        String message = new String(frame.getPayload());
                        executeMessage.setMessage(message);
                        executeMessage.setExitValue(0);
                        System.out.println("输出结果：" + message);
                    }
                    super.onNext(frame);
                }
            };
            final long[] maxMemory = {0L};
            StatsCmd statsCmd = dockerClient.statsCmd(containerResponseId);
            ResultCallback<Statistics> resultCallback = new ResultCallback<Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    // 获取内存占用
                    System.out.println("获取占用内存：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }

                @Override
                public void close() {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            };
            statsCmd.exec(resultCallback);
            try {
                stopWatch.start();
                dockerClient.execStartCmd(createCmdResponseId)
                        .exec(execStartResultCallback)
                        // TIME_OUT, 最长执行时间，接着往下走不会抛异常，参数二：单位
                        // todo 实际运行会造成输出结果为空，记得先暂时去除
                        // TIME_OUT, TimeUnit.MICROSECONDS
                        .awaitCompletion();
                stopWatch.stop();
                // 关闭
                statsCmd.close();
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            long lastTaskTimeMillis = stopWatch.getLastTaskTimeMillis();
            System.out.println("获取占用时间：" + lastTaskTimeMillis);
            executeMessage.setTime(lastTaskTimeMillis);
            executeMessage.setMemory(maxMemory[0]);
            // 添加进程执行信息
            executeMessageList.add(executeMessage);
        }
        return executeMessageList;
    }
}