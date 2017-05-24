package com.everhomes.ideaplugin.unittool;

import com.everhomes.ideaplugin.config.IdeaToolsSetting;
import com.everhomes.ideaplugin.unittool.bean.CmdEntry;
import com.everhomes.ideaplugin.util.Util;
import com.everhomes.ideaplugin.util.VelocityUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by xq.tian on 2017/5/23.
 */
class UnitToolService {

    private static final Logger log = Util.getLogger(UnitToolService.class);

    private static final String REQUEST_MAPPING_ANNO = "@SuppressWarnings";

    private static final String JAVA_UTIL_LIST_REGEX = "java\\.util\\.List<(.*?)>";
    private static final String REQUEST_MAPPING_REGEX = "@SuppressWarnings\\(\"(.*?)\"\\)";

    private List<String> importList = Lists.newArrayList();
    private Project project;
    private String currClzName;
    private PsiMethod currMethod;
    private String dirPath = "src/com/everhomes/unittool";
    private String packageName = "com.everhomes.test.junit";
    private PsiClass testClz;

    private PsiJavaFile testJavaFile;
    private IdeaToolsSetting settings;

    static String genUnitTest(PsiClass psiClass, PsiElement element, IdeaToolsSetting settings) {
        UnitToolService service = UnitToolService.getInstance();
        service.settings = settings;
        return service.generateUnitTest(psiClass, element);
    }

    private static UnitToolService getInstance() {
        return new UnitToolService();
    }

    private String generateUnitTest(PsiClass psiClass, PsiElement element) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) {
            return "Do not find psiClass qualifiedName!";
        }

        if (!qualifiedName.endsWith("Controller")) {
            return "Please choose a controller!";
        }

        String clzBody = psiClass.getText();
        String clzRequestMapping = this.getRequestMapping(clzBody);

        currMethod = this.getCurrentMethod(psiClass, element);

        if (currMethod == null) {
            return "Please choose a method in controller!";
        }

        String methodBody = currMethod.getText();
        String methodRequestMapping = this.getRequestMapping(methodBody);

        final String requestMapping = clzRequestMapping + "/" + methodRequestMapping;
        log.debug("find request mapping is " + requestMapping);

        final List<String> cmdList = getCmdList(currMethod);

        project = psiClass.getProject();
        currClzName = psiClass.getName();

        final String testClassName = currClzName.substring(0, currClzName.indexOf("Controller")) + "Test";

        // PsiClass selectedClass = Util.chooseClass(project, psiClass);
        //
        // PsiDirectory psiDirectory = PsiUtil.browseForFile(project);
        /*UnitToolDialog dialog = new UnitToolDialog();
        dialog.project = project;
        dialog.pack();
        dialog.setVisible(true);*/

        final List<CmdEntry> entryList = this.getCmdEntries(cmdList);

        String[] pathSplit = dirPath.split("/");
        List<String> pathList = new ArrayList<>();
        Collections.addAll(pathList, pathSplit);

        WriteCommandAction.runWriteCommandAction(project, () -> {

            getOrCreateTestClass(testClassName);

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            processImportList(factory);

            Map<String, Object> map = Maps.newHashMap();
            map.put("requestUriName", currMethod.getName() + "URL");
            map.put("requestUri", requestMapping);
            String template = settings.getTemplate("Const");
            String content = VelocityUtil.evaluate(template, map);
            // content = content.replaceAll("\\s\\s+", "");

            PsiField field = factory.createFieldFromText(content, testClz);

            map = Maps.newHashMap();
            map.put("methodName", currMethod.getName());
            map.put("requestUriName", currMethod.getName() + "URL");
            map.put("setterBlock", entryList);
            map.put("render", new CmdEntryRender());
            template = settings.getTemplate("Method");
            content = VelocityUtil.evaluate(template, map);
            // content = content.replaceAll("\\s\\s+", "");

            PsiMethod method = factory.createMethodFromText(content, testClz);

            testClz.add(field);
            testClz.addBefore(method, testClz.getMethods()[0]);

            CodeStyleManager.getInstance(project).reformat(testClz);

            FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(project, testClz.getContainingFile().getVirtualFile()), true);
        });

        return "OK";
    }

    private void processImportList(PsiElementFactory factory) {
        for (String im : importList) {
            PsiClass classByQName = Util.getClassByQName(project, im);
            PsiImportStatement importStatement = factory.createImportStatement(classByQName);
            List<String> stringList = Util.getImportList(testJavaFile);
            if (!stringList.contains(im)) {
                testJavaFile.getImportList().add(importStatement);
            }
        }
    }

    private void getOrCreateTestClass(String testClassName) {
        VirtualFileManager manager = VirtualFileManager.getInstance();
        String outputFilePath = project.getBasePath() + "/" + dirPath + "/" + testClassName + ".java";
        String url = VfsUtil.pathToUrl(outputFilePath);

        VirtualFile virtualFile = manager.refreshAndFindFileByUrl(url);

        if (virtualFile != null && virtualFile.exists()) {
            testClz = Util.getClassByQName(project, packageName + "." + testClassName);
            testJavaFile = (PsiJavaFile) testClz.getContainingFile();
        } else {
            File file = new File(outputFilePath);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdir();
            }
            try (FileWriter fileWriter = new FileWriter(file)) {
                HashMap<String, Object> map = Maps.newHashMap();
                map.put("packageName", packageName);
                map.put("testClassName", testClassName);
                String classTemplate = settings.getTemplate("Class");
                String content = VelocityUtil.evaluate(classTemplate, map);
                fileWriter.write(content);
            } catch (Exception e) {
                log.error("some err", e);
            }

            manager.refreshAndFindFileByUrl(url);

            testClz = Util.getClassByQName(project, packageName + "." + testClassName);
            testJavaFile = (PsiJavaFile) testClz.getContainingFile();

            /*testClz = JavaDirectoryService.getInstance().createClass(
                    dir, clazzName, "Class", true, Maps.newHashMap());*/

            testJavaFile.setPackageName(packageName);
        }
    }

    @NotNull
    private List<CmdEntry> getCmdEntries(List<String> cmdList) {
        List<CmdEntry> entryList = Lists.newArrayList();
        for (String cmd : cmdList) {
            PsiClass aClass = Util.getClassByQName(project, cmd);
            PsiField[] allFields = aClass.getAllFields();
            List<CmdEntry> setters = processCmdEntries(allFields);
            entryList.addAll(setters);
        }
        return entryList;
    }

    private List<String> getCmdList(PsiMethod currMethod) {
        List<String> cmdList = Lists.newArrayList();
        PsiParameterList parameterList = currMethod.getParameterList();
        for (PsiParameter param : parameterList.getParameters()) {
            PsiTypeElement typeElement = param.getTypeElement();
            if (typeElement != null) {
                String cmdQualifyName = param.getType().getCanonicalText();
                cmdList.add(cmdQualifyName);
                importList.add(cmdQualifyName);
            }
        }
        return cmdList;
    }

    @Nullable
    private PsiMethod getCurrentMethod(PsiClass psiClass, PsiElement element) {
        PsiMethod currentMethod = null;
        PsiMethod[] allMethods = psiClass.getAllMethods();
        for (PsiMethod method : allMethods) {
            if (method.getName().equals(element.getText())) {
                currentMethod = method;
                break;
            }
        }
        return currentMethod;
    }

    private List<CmdEntry> processCmdEntries(PsiField[] allFields) {
        List<CmdEntry> entries = Lists.newArrayList();
        for (PsiField field : allFields) {
            CmdEntry newSetter = new CmdEntry();
            processCmdEntries(field, newSetter);
            entries.add(newSetter);
        }
        return entries;
    }

    private CmdEntry processCmdEntries(PsiField field, CmdEntry setter) {
        String fieldName = field.getName();
        String fieldQualifiedName = field.getType().getCanonicalText();

        switchCmdType(setter, fieldName, fieldQualifiedName);
        return setter;
    }

    private void switchCmdType(CmdEntry entry, String fieldName, String fieldQualifiedName) {
        PsiClass fieldClass = Util.getClassByQName(project, fieldQualifiedName);
        entry.setName(fieldName);
        switch (fieldQualifiedName) {
            case "java.lang.String":
                entry.setValue(String.format("\"%s\"", fieldName));
                break;
            case "java.lang.Byte":
                entry.setValue("(byte) 1");
                break;
            case "java.lang.Long":
                entry.setValue("1L");
                break;
            default:
                if (fieldQualifiedName.startsWith("java.util.List")) {
                    importList.add("java.util.List");
                    importList.add("java.util.ArrayList");

                    Pattern compile = Pattern.compile(JAVA_UTIL_LIST_REGEX);
                    Matcher matcher = compile.matcher(fieldQualifiedName);
                    String qualifiedName;
                    if (matcher.find()) {
                        qualifiedName = matcher.group(1);
                        PsiClass typeClass = Util.getClassByQName(project, qualifiedName);
                        entry.getListChild().addAll(processTypedEntry(project, typeClass, fieldName));
                        entry.setList(true);
                        entry.setValue(fieldName + "List");
                        entry.setType(typeClass.getName());
                    } else {
                        log.error("do not find list typed" + fieldQualifiedName);
                    }
                } else if (fieldQualifiedName.startsWith("com.everhomes.rest")) {
                    importList.add(fieldQualifiedName);
                    entry.getCmdChild().addAll(processCmdEntries(fieldClass.getAllFields()));
                    entry.setValue(fieldName);
                    entry.setHasChild(true);
                    entry.setType(fieldClass.getName());
                }
                break;
        }
    }

    private Collection<? extends CmdEntry> processTypedEntry(Project project, PsiClass typeClass, String fieldName) {
        List<CmdEntry> entries = Lists.newArrayList();
        CmdEntry entry = new CmdEntry();
        switchCmdType(entry, fieldName, typeClass.getQualifiedName());

        entries.add(entry);
        entries.add(entry);
        return entries;
    }

    /*private PsiDirectory getOrCreatePsiDirectory(List<String> dirPaths, Project project, VirtualFile vf) {
        String path = dirPaths.remove(0);

        PsiDirectory baseDir = PsiDirectoryFactory.getInstance(project).createDirectory(vf);
        PsiDirectory subDir = baseDir.findSubdirectory(path);

        boolean notExist = subDir == null;
        if (notExist) {
            subDir = baseDir.createSubdirectory(path);
        }

        if (dirPaths.size() > 0) {
            return getOrCreatePsiDirectory(dirPaths, project, subDir.getVirtualFile());
        } else {
            return subDir;
        }
    }*/

    private String getRequestMapping(String methodBody) {
        String requestMapping = "";
        if (methodBody.contains(REQUEST_MAPPING_ANNO)) {
            Pattern pattern = Pattern.compile(REQUEST_MAPPING_REGEX);
            Matcher matches = pattern.matcher(methodBody);
            if (matches.find()) {
                requestMapping = matches.group(1);
            } else {
                log.warn("do not find request mapping");
            }
        } else {
            log.warn("do not find request mapping");
        }
        return requestMapping;
    }
}