using Fixture.Player;
using UnityEngine;

namespace Fixture.Cameras
{
    /// <summary>
    /// Third-person follow camera. Runs in LateUpdate so it sees the player's final
    /// transform for the frame.
    /// </summary>
    public class CameraFollow : MonoBehaviour
    {
        [SerializeField]
        private Transform target;

        [SerializeField]
        private Vector3 offset = new Vector3(0f, 2.1f, -4.5f);

        [SerializeField]
        private float followDamping = 12f;

        [SerializeField]
        private float lookDamping = 18f;

        [SerializeField]
        private float landingDipDamping = 4.5f;

        private Vector3 followVelocity;
        private PlayerController player;

        private void LateUpdate()
        {
            if (target == null)
            {
                return;
            }

            FollowTarget();
            AimAtTarget();
        }

        /// <summary>
        /// Moves the camera towards the target's offset position, damping the vertical
        /// axis separately while the target is descending.
        /// </summary>
        private void FollowTarget()
        {
            Vector3 desired = target.TransformPoint(offset);
            float verticalRate = player != null && !player.IsGrounded ? landingDipDamping : followDamping;

            Vector3 next = Vector3.SmoothDamp(
                transform.position,
                desired,
                ref followVelocity,
                1f / followDamping);

            next.y = Mathf.Lerp(transform.position.y, desired.y, verticalRate * Time.deltaTime);
            transform.position = next;
        }

        /// <summary>
        /// Rotates the camera towards the target's head height.
        /// </summary>
        private void AimAtTarget()
        {
            Vector3 focus = target.position + (Vector3.up * 1.4f);
            Quaternion desired = Quaternion.LookRotation(focus - transform.position, Vector3.up);

            transform.rotation = Quaternion.Slerp(
                transform.rotation,
                desired,
                lookDamping * Time.deltaTime);
        }
    }
}
