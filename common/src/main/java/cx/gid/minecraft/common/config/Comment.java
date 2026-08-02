package cx.gid.minecraft.common.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documentation for a config field, written into the generated reference file.
 *
 * Javadoc would be the natural home for this, but it is discarded at compile time and so
 * cannot be read when the reference file is generated. Annotating the field keeps the
 * explanation next to what it explains, which a separate documentation file never manages
 * for long.
 *
 * Each string is one line, so a multi-line explanation stays readable in source:
 * <pre>{@code
 * @Comment({"How many of the material buy one emerald.",
 *           "Vanilla uses 10–32 depending on how common it is."})
 * public int count = 15;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Comment {
    String[] value();
}
