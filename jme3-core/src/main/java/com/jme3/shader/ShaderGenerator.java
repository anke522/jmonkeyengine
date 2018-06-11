/*
 * Copyright (c) 2009-2018 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.shader;

import com.jme3.asset.AssetManager;
import com.jme3.material.ShaderGenerationInfo;
import com.jme3.material.TechniqueDef;
import com.jme3.material.plugins.ConditionParser;
import com.jme3.shader.Shader.ShaderType;
import com.jme3.shader.plugins.ShaderAssetKey;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is the base for a shader generator using the ShaderNodes system,
 * it contains basis mechanism of generation, but no actual generation code.
 * This class is abstract, any Shader generator must extend it.
 *
 * @author Nehon
 */
public abstract class ShaderGenerator {

    public static final String NAME_SPACE_GLOBAL = "Global";
    public static final String NAME_SPACE_VERTEX_ATTRIBUTE = "Attr";
    public static final String NAME_SPACE_MAT_PARAM = "MatParam";
    public static final String NAME_SPACE_WORLD_PARAM = "WorldParam";

    /**
     * the asset manager
     */
    protected AssetManager assetManager;
    /**
     * indentation value for generation
     */
    protected int indent;
    /**
     * the technique def to use for the shader generation
     */
    protected TechniqueDef techniqueDef = null;
    /**
     * Extension pattern
     */
    Pattern extensions = Pattern.compile("(#extension.*\\s+)");

    /**
     * a set of imports to append to the shader source
     */
    private Set<String> imports = new HashSet<>();

    /**
     * The nodes function and their condition to be declared in the shader
     */
    protected Map<String, NodeDeclaration> declaredNodes = new LinkedHashMap<>();

    /**
     * Build a shaderGenerator
     *
     * @param assetManager
     */
    protected ShaderGenerator(AssetManager assetManager) {
        this.assetManager = assetManager;        
    }
    
    public void initialize(TechniqueDef techniqueDef){
        this.techniqueDef = techniqueDef;
    }
    
    /**
     * Generate vertex and fragment shaders for the given technique
     *
     * @return a Shader program
     */
    public Shader generateShader(String definesSourceCode) {
        if (techniqueDef == null) {
            throw new UnsupportedOperationException("The shaderGenerator was not "
                    + "properly initialized, call "
                    + "initialize(TechniqueDef) before any generation");
        }

        String techniqueName = techniqueDef.getName();
        ShaderGenerationInfo info = techniqueDef.getShaderGenerationInfo();

        Shader shader = new Shader();
        for (ShaderType type : ShaderType.values()) {
            String extension = type.getExtension();
            String language = getLanguageAndVersion(type);
            String shaderSourceCode = buildShader(techniqueDef.getShaderNodes(), info, type);
            
            if (shaderSourceCode != null) {
                String shaderSourceAssetName = techniqueName + "." + extension;
                shader.addSource(type, shaderSourceAssetName, shaderSourceCode, definesSourceCode, language);
            }
        }
        
        techniqueDef = null;
        return shader;
    }

    /**
     * This method is responsible for the shader generation.
     *
     * @param shaderNodes the list of shader nodes
     * @param info the ShaderGenerationInfo filled during the Technique loading
     * @param type the type of shader to generate
     * @return the code of the generated vertex shader
     */
    protected String buildShader(List<ShaderNode> shaderNodes, ShaderGenerationInfo info, ShaderType type) {
        if (type == ShaderType.TessellationControl ||
            type == ShaderType.TessellationEvaluation || 
            type == ShaderType.Geometry) {
            // TODO: Those are not supported.
            // Too much code assumes that type is either Vertex or Fragment
            return null;
        }

        imports.clear();

        indent = 0;

        StringBuilder sourceDeclaration = new StringBuilder();
        StringBuilder source = new StringBuilder();

        generateUniforms(sourceDeclaration, info, type);

        if (type == ShaderType.Vertex) {
            generateAttributes(sourceDeclaration, info);
        }
        generateVaryings(sourceDeclaration, info, type);

        generateStartOfMainSection(source, info, type);

        generateDeclarationAndMainBody(shaderNodes, sourceDeclaration, source, info, type);

        generateEndOfMainSection(source, info, type);

        sourceDeclaration.append(source);

        return moveExtensionsUp(sourceDeclaration);
    }

    /**
     * parses the source and moves all the extensions at the top of the shader source as having extension declarations
     * in the middle of a shader is against the specs and not supported by all drivers.
     * @param sourceDeclaration
     * @return
     */
    private String moveExtensionsUp(StringBuilder sourceDeclaration) {
        Matcher m = extensions.matcher( sourceDeclaration.toString());
        StringBuilder finalSource = new StringBuilder();
        while(m.find()){
            finalSource.append(m.group());
        }
        finalSource.append(m.replaceAll(""));
        return finalSource.toString();
    }

    /**
     * iterates through shader nodes to load them and generate the shader
     * declaration part and main body extracted from the shader nodes, for the
     * given shader type
     *
     * @param shaderNodes the list of shader nodes
     * @param sourceDeclaration the declaration part StringBuilder of the shader
     * to generate
     * @param source the main part StringBuilder of the shader to generate
     * @param info the ShaderGenerationInfo
     * @param type the Shader type
     */
    protected void generateDeclarationAndMainBody(List<ShaderNode> shaderNodes, StringBuilder sourceDeclaration, StringBuilder source, ShaderGenerationInfo info, Shader.ShaderType type) {
        declaredNodes.clear();
        for (ShaderNode shaderNode : shaderNodes) {
            if (info.getUnusedNodes().contains(shaderNode.getName())) {
                continue;
            }

            if (shaderNode.getDefinition().getType() == type) {
                int index = findShaderIndexFromVersion(shaderNode, type);
                String shaderPath = shaderNode.getDefinition().getShadersPath().get(index);
                Map<String, String> sources = (Map<String, String>) assetManager.loadAsset(new ShaderAssetKey(shaderPath, false));
                String loadedSource = sources.get("[main]");
                for (String name : sources.keySet()) {
                    if (!name.equals("[main]") && !imports.contains(name)) {
                        imports.add(name);
                        // append the imported file in place if it hasn't been imported already.
                        sourceDeclaration.append(sources.get(name));
                    }
                }
                // Nodes are functions added to the declaration part of the shader
                // Multiple nodes may use the same definition and we don't want to declare it several times.
                // Also nodes can have #ifdef conditions so we need to properly merge this conditions to declare the Node function.
                NodeDeclaration nd = declaredNodes.get(shaderNode.getDefinition().getName());
                loadedSource =  functionize(loadedSource, shaderNode.getDefinition());
                if(nd == null){
                    nd = new NodeDeclaration(shaderNode.getCondition(),  loadedSource);
                    declaredNodes.put(shaderNode.getDefinition().getName(), nd);
                } else {
                    nd.condition = ConditionParser.mergeConditions(nd.condition, shaderNode.getCondition(), "||");
                }

                generateNodeMainSection(source, shaderNode, loadedSource, info);
            }
        }

        generateDeclarationSection(sourceDeclaration);

    }

    /**
     * Tuns old style shader node code into a proper function so that it can be appended to the declarative sectio.
     * Note that this only needed for nodes coming from a j3sn file.
     * @param source
     * @param def
     * @return
     */
    public static String functionize(String source, ShaderNodeDefinition def){
        StringBuffer signature = new StringBuffer();
        def.setReturnType("void");
        signature.append("void ").append(def.getName()).append("(");
        boolean addParam = false;
        if(def.getParams().isEmpty()){
            addParam = true;
        }

        boolean isFirst = true;
        for (ShaderNodeVariable v : def.getInputs()) {
            if(!isFirst){
                signature.append(", ");
            }
            String qualifier;
            qualifier = "const in";
            signature.append(qualifier).append(" ").append(v.getType()).append(" ").append(v.getName());
            isFirst = false;
            if(addParam) {
                def.getParams().add(v);
            }
        }

        for (ShaderNodeVariable v : def.getOutputs()) {
            if(def.getInputs().contains(v)){
                continue;
            }
            if(!isFirst){
                signature.append(", ");
            }
            signature.append("out ").append(v.getType()).append(" ").append(v.getName());
            isFirst = false;
            if(addParam) {
                def.getParams().add(v);
            }
        }
        signature.append("){");

        source = source.replaceAll("\\s*void\\s*main\\s*\\(\\s*\\)\\s*\\{", signature.toString());

        return source;
    }

    /**
     * returns the language + version of the shader should be something like
     * "GLSL100" for glsl 1.0 "GLSL150" for glsl 1.5.
     *
     * @param type the shader type for which the version should be returned.
     *
     * @return the shaderLanguage and version.
     */
    protected abstract String getLanguageAndVersion(Shader.ShaderType type);

    /**
     * generates the uniforms declaration for a shader of the given type.
     *
     * @param source the source StringBuilder to append generated code.
     * @param info the ShaderGenerationInfo.
     * @param type the shader type the uniforms have to be generated for.
     */
    protected abstract void generateUniforms(StringBuilder source, ShaderGenerationInfo info, ShaderType type);

    /**
     * generates the attributes declaration for the vertex shader. There is no
     * Shader type passed here as attributes are only used in vertex shaders
     *
     * @param source the source StringBuilder to append generated code.
     * @param info the ShaderGenerationInfo.
     */
    protected abstract void generateAttributes(StringBuilder source, ShaderGenerationInfo info);

    /**
     * generates the varyings for the given shader type shader. Note that
     * varyings are deprecated in glsl 1.3, but this method will still be called
     * to generate all non global inputs and output of the shaders.
     *
     * @param source the source StringBuilder to append generated code.
     * @param info the ShaderGenerationInfo.
     * @param type the shader type the varyings have to be generated for.
     */
    protected abstract void generateVaryings(StringBuilder source, ShaderGenerationInfo info, ShaderType type);

    /**
     * Appends the shaderNodes function to the shader declarative
     * part.
     * 
     * @param source the StringBuilder to append generated code.
     */
    protected abstract void generateDeclarationSection(StringBuilder source);

    /**
     * generates the start of the shader main section. this method is
     * responsible of appending the "void main(){" in the shader and declaring
     * all global outputs of the shader
     *
     * @param source the StringBuilder to append generated code.
     * @param info the ShaderGenerationInfo.
     * @param type the shader type the section has to be generated for.
     */
    protected abstract void generateStartOfMainSection(StringBuilder source, ShaderGenerationInfo info, ShaderType type);

    /**
     * generates the end of the shader main section. this method is responsible
     * of appending the last "}" in the shader and mapping all global outputs of
     * the shader
     *
     * @param source the StringBuilder to append generated code.
     * @param info the ShaderGenerationInfo.
     * @param type the shader type the section has to be generated for.
     */
    protected abstract void generateEndOfMainSection(StringBuilder source, ShaderGenerationInfo info, ShaderType type);

    /**
     * Appends the given shaderNode main part to the shader declarative part. If
     * needed the shader type can be determined by fetching the shaderNode's
     * definition type.
     *
     * @see ShaderNode#getDefinition()
     * @see ShaderNodeDefinition#getType()
     *
     * @param source the StringBuilder to append generated code.
     * @param shaderNode the shaderNode.
     * @param nodeSource the declaration part of the loaded shaderNode source.
     * @param info the ShaderGenerationInfo.
     */
    protected abstract void generateNodeMainSection(StringBuilder source, ShaderNode shaderNode, String nodeSource, ShaderGenerationInfo info);

    /**
     * returns the shaderpath index according to the version of the generator.
     * This allow to select the higher version of the shader that the generator
     * can handle
     *
     * @param shaderNode the shaderNode being processed
     * @param type the shaderType
     * @return the index of the shader path in ShaderNodeDefinition shadersPath
     * list
     * @throws NumberFormatException
     */
    protected int findShaderIndexFromVersion(ShaderNode shaderNode, ShaderType type) throws NumberFormatException {
        int index = 0;
        List<String> lang = shaderNode.getDefinition().getShadersLanguage();
        int genVersion = Integer.parseInt(getLanguageAndVersion(type).substring(4));
        int curVersion = 0;
        for (int i = 0; i < lang.size(); i++) {
            int version = Integer.parseInt(lang.get(i).substring(4));
            if (version > curVersion && version <= genVersion) {
                curVersion = version;
                index = i;
            }
        }
        return index;
    }    

    protected class NodeDeclaration{
        String condition;
        String source;

        public NodeDeclaration(String condition, String source) {
            this.condition = condition;
            this.source = source;
        }
    }

}
