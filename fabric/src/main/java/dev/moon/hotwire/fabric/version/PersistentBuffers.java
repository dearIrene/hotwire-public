package dev.moon.hotwire.fabric.version;

//? if >=1.21.11 && <26.1 {
/*import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL45C;
*///?}

public final class PersistentBuffers {
    private PersistentBuffers() {
    }

    //? if >=1.21.11 && <26.1 {
    /*// Whether a buffer's mapping takes the game's flush (GlBufferMixin).
    public interface Mapped {
        boolean hotwire$takesFlush();
    }

    public static boolean isFlushedExplicitly(int handle) {
        int access;
        if (GL.getCapabilities().OpenGL45) {
            access = GL45C.glGetNamedBufferParameteri(handle, GL30C.GL_BUFFER_ACCESS_FLAGS);
        } else {
            int bound = GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, handle);
            access = GL15C.glGetBufferParameteri(GL31C.GL_COPY_READ_BUFFER, GL30C.GL_BUFFER_ACCESS_FLAGS);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, bound);
        }
        return access == 0 || (access & GL30C.GL_MAP_FLUSH_EXPLICIT_BIT) != 0;
    }
    *///?}
}
