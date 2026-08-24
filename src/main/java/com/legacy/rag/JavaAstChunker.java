package com.legacy.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 소스를 {@code javaparser-core}(symbol-solver 없이 구문 구조만 사용)로 파싱해 클래스
 * skeleton 청크(클래스 선언+필드+메서드 시그니처만, 본문 생략)와 메서드별 청크(메서드 전체
 * 본문)를 만든다(TASK-001, REQ-2). 파싱 실패(문법 오류 등)는 예외를 던지지 않고 {@code null}을
 * 반환한다 — 호출부({@link ChunkerRouter})가 이 null을 신호로 자동으로 폴백 경로로 넘어가게
 * 설계했다(REQ-3, 사일런트 스킵 금지 — 최종적으로는 폴백 청커가 최소 1개 청크를 만든다).
 */
class JavaAstChunker {

    private static final Logger log = LoggerFactory.getLogger(JavaAstChunker.class);

    private final JavaParser parser;

    JavaAstChunker() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(config);
    }

    /**
     * @param filePath   청크 메타데이터에 채울 파일 경로
     * @param sourceCode Java 소스 전체 텍스트
     * @return 클래스 skeleton 청크 + 메서드/생성자 청크 목록. 파싱 실패 시 {@code null}
     */
    List<CodeChunk> chunk(String filePath, String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return null;
        }

        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(sourceCode);
        } catch (Exception e) {
            log.debug("[Java AST 청킹 실패] filePath={} {}", filePath, e.getMessage());
            return null;
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.debug("[Java AST 파싱 실패] filePath={} problems={}", filePath, result.getProblems());
            return null;
        }
        CompilationUnit cu = result.getResult().get();

        List<CodeChunk> chunks = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            addSkeletonChunk(filePath, type, chunks);
        }
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            addCallableChunk(filePath, method, method.getBody().orElse(null), "method", chunks);
        }
        for (ConstructorDeclaration constructor : cu.findAll(ConstructorDeclaration.class)) {
            addCallableChunk(filePath, constructor, constructor.getBody(), "constructor", chunks);
        }

        if (chunks.isEmpty()) {
            // 파싱 자체는 성공했지만(예: 필드/인터페이스만 있는 파일 등) 클래스도 메서드도
            // 하나도 못 뽑은 경우 — 폴백으로 넘기는 게 더 안전하다(REQ-3).
            return null;
        }
        return chunks;
    }

    /** 클래스 선언+필드+메서드 시그니처만 남기고 본문은 생략한 skeleton 청크를 만든다. */
    private void addSkeletonChunk(String filePath, ClassOrInterfaceDeclaration type, List<CodeChunk> out) {
        if (type.getBegin().isEmpty() || type.getEnd().isEmpty()) {
            return;
        }
        int startLine = type.getBegin().get().line;
        int endLine = type.getEnd().get().line;

        ClassOrInterfaceDeclaration skeleton = type.clone();
        List<BodyDeclaration<?>> members = new ArrayList<>(skeleton.getMembers());
        for (BodyDeclaration<?> member : members) {
            if (member instanceof MethodDeclaration method) {
                method.setBody(null); // 본문 제거 → 시그니처만 세미콜론으로 출력됨
            } else if (member instanceof ConstructorDeclaration constructor) {
                constructor.setBody(new BlockStmt()); // 생성자는 본문 필수라 빈 블록으로 대체
            } else if (member instanceof FieldDeclaration) {
                // 필드는 그대로 유지
            } else if (member instanceof TypeDeclaration) {
                // 중첩 타입은 별도 findAll 순회에서 자기 자신의 skeleton 청크로 따로 만들어지므로
                // 여기서는 중복/비대화를 막기 위해 제거한다.
                skeleton.remove(member);
            } else {
                // 초기화 블록 등 나머지 멤버는 skeleton 목적과 무관해 제거한다.
                skeleton.remove(member);
            }
        }

        String content = skeleton.toString();
        String symbolName = type.getNameAsString();
        CodeChunk chunk = new CodeChunk(filePath, startLine, endLine, content, symbolName, "class-skeleton");
        out.addAll(ChunkSplitter.enforceHardCap(chunk));
    }

    /** 메서드/생성자 전체 본문을 원본 소스 그대로(주석/포맷 보존) 청크로 만든다. */
    private void addCallableChunk(String filePath, Node node, BlockStmt body, String symbolType, List<CodeChunk> out) {
        if (body == null || node.getBegin().isEmpty() || node.getEnd().isEmpty()) {
            return; // 추상 메서드/인터페이스 메서드 등 본문이 없으면 청크 대상이 아니다.
        }
        int startLine = node.getBegin().get().line;
        int endLine = node.getEnd().get().line;
        String content = node.toString();

        String symbolName;
        if (node instanceof MethodDeclaration method) {
            symbolName = method.getNameAsString();
        } else if (node instanceof ConstructorDeclaration constructor) {
            symbolName = constructor.getNameAsString();
        } else {
            symbolName = null;
        }

        CodeChunk chunk = new CodeChunk(filePath, startLine, endLine, content, symbolName, symbolType);
        out.addAll(ChunkSplitter.enforceHardCap(chunk));
    }
}
