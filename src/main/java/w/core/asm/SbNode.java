package w.core.asm;

import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author Frank
 * @date 2024/6/22 18:49
 */
public class SbNode {
    String constString;

    int loadType;

    int loadIndex;


    public SbNode(String constString) {
        this.constString = constString;
    }

    public SbNode(int loadType, int loadIndex) {
        if (loadType != ALOAD && loadType != LLOAD) {
            throw new IllegalArgumentException("Unsupported load type in SubStringNode: " + loadType);
        }
        this.loadType = loadType;
        this.loadIndex = loadIndex;
    }

    public void loadAndAppend(MethodVisitor mv) {
        if (constString != null) {
            mv.visitLdcInsn(constString);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false);
        } else {
            mv.visitVarInsn(loadType, loadIndex);
            switch (loadType) {
                case ALOAD:
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false);
                    break;
                case LLOAD:
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", false);
                    break;
                default:
                    throw new IllegalStateException("Unsupported load type in SubStringNode: " + loadType);
            }
        }
    }

}
