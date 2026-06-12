package com.agustinbanchio.excalidraw.file

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class ExcalidrawParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ExcalidrawLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY

    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        object : PsiFileBase(viewProvider, ExcalidrawLanguage) {
            override fun getFileType(): FileType = ExcalidrawFileType

            override fun toString(): String = "Excalidraw File"
        }

    override fun spaceExistenceTypeBetweenTokens(
        left: ASTNode,
        right: ASTNode,
    ): ParserDefinition.SpaceRequirements = ParserDefinition.SpaceRequirements.MAY

    private class ExcalidrawLexer : LexerBase() {
        private var buffer: CharSequence = ""
        private var bufferEnd = 0
        private var position = 0

        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
            this.buffer = buffer
            bufferEnd = endOffset
            position = startOffset
        }

        override fun getState(): Int = 0

        override fun getTokenType(): IElementType? = if (position < bufferEnd) CONTENT else null

        override fun getTokenStart(): Int = position

        override fun getTokenEnd(): Int = if (position < bufferEnd) bufferEnd else position

        override fun advance() {
            position = bufferEnd
        }

        override fun getBufferSequence(): CharSequence = buffer

        override fun getBufferEnd(): Int = bufferEnd
    }

    companion object {
        private val CONTENT = IElementType("EXCALIDRAW_CONTENT", ExcalidrawLanguage)
        private val FILE = IFileElementType(ExcalidrawLanguage)
    }
}
