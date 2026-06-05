"""
Merge all Mixamo FBX animations (each exported "with skin") into a single GLB
that SceneView / Filament can load.

Strategy:
  1. Import every FBX. Each one brings its own copy of the rigged character plus
     one animation Action.
  2. Keep the FIRST character (armature + mesh) as the base. For every other
     import, steal its Action (rename to the file name, give it a fake user so it
     survives) and delete the duplicate armature/mesh objects.
  3. Export the base character once, with export_animation_mode='ACTIONS', so all
     the collected Actions become separate, named glTF animations sharing one mesh.

Source FBX live in avatar_sources/animations/ (kept out of the app assets so the
~47 MB of FBX never ship in the APK). The output GLB goes into the app assets.

Run (from the repo root):
  blender --background --factory-startup --python scripts/merge_animations.py -- ^
    avatar_sources/animations ^
    android/app/src/main/assets/models/avatar.glb
"""

import bpy
import os
import sys
import glob


def main():
    argv = sys.argv
    argv = argv[argv.index("--") + 1:]
    anim_dir, out_glb = argv[0], argv[1]

    # Start from a guaranteed-empty scene.
    bpy.ops.wm.read_factory_settings(use_empty=True)

    fbx_files = sorted(glob.glob(os.path.join(anim_dir, "*.fbx")))
    print(f"[merge] found {len(fbx_files)} fbx files in {anim_dir}")
    if not fbx_files:
        raise SystemExit("[merge] no FBX files found")

    base_armature = None
    base_meshes = []
    actions = []

    for fbx in fbx_files:
        clip_name = os.path.splitext(os.path.basename(fbx))[0]
        before = set(bpy.data.objects)
        bpy.ops.import_scene.fbx(filepath=fbx)
        new_objs = [o for o in bpy.data.objects if o not in before]

        arm = next((o for o in new_objs if o.type == "ARMATURE"), None)
        meshes = [o for o in new_objs if o.type == "MESH"]

        # Pull out this import's animation and give it a stable name.
        if arm and arm.animation_data and arm.animation_data.action:
            act = arm.animation_data.action
            act.name = clip_name
            act.use_fake_user = True  # survive object deletion
            actions.append(act)
            print(f"[merge] {clip_name}: captured action")
        else:
            print(f"[merge] WARNING {clip_name}: no action found")

        if base_armature is None:
            base_armature = arm
            base_meshes = meshes
            print(f"[merge] base character set from {clip_name} "
                  f"(meshes: {[m.name for m in meshes]})")
        else:
            # Drop the duplicate character; we only kept its action.
            for o in new_objs:
                try:
                    bpy.data.objects.remove(o, do_unlink=True)
                except Exception as e:  # noqa: BLE001
                    print(f"[merge] could not remove {o.name}: {e}")

    if base_armature is None:
        raise SystemExit("[merge] no armature imported; aborting")

    # Select only the base character for export.
    bpy.ops.object.select_all(action="DESELECT")
    base_armature.select_set(True)
    for m in base_meshes:
        m.select_set(True)
    bpy.context.view_layer.objects.active = base_armature

    os.makedirs(os.path.dirname(out_glb), exist_ok=True)

    bpy.ops.export_scene.gltf(
        filepath=out_glb,
        export_format="GLB",
        use_selection=True,
        export_animations=True,
        export_animation_mode="ACTIONS",
        export_apply=False,
        export_yup=True,
    )

    size = os.path.getsize(out_glb) if os.path.exists(out_glb) else 0
    print(f"[merge] EXPORTED {out_glb} ({size} bytes) with {len(actions)} actions")
    print("[merge] clip names: " + ", ".join(a.name for a in actions))


if __name__ == "__main__":
    main()
