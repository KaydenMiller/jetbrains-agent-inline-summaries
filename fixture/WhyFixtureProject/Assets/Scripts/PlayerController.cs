using UnityEngine;

namespace Fixture.Player
{
    /// <summary>
    /// Ground movement and jumping for the player capsule.
    ///
    /// Physics runs in FixedUpdate; input is read in Update and consumed by the next
    /// physics step. Nothing here talks to the animator: PlayerVisuals reads the
    /// public state properties instead.
    /// </summary>
    [RequireComponent(typeof(Rigidbody))]
    [RequireComponent(typeof(CapsuleCollider))]
    public class PlayerController : MonoBehaviour
    {
        [Header("Movement")]
        [SerializeField]
        private float walkSpeed = 4.5f;

        [SerializeField]
        private float sprintSpeed = 7.25f;

        [SerializeField]
        private float acceleration = 40f;

        [Header("Jump")]
        [SerializeField]
        private float jumpImpulse = 6.4f;

        [SerializeField]
        private float jumpBufferMs = 120f;
        [SerializeField]
        private float coyoteTimeMs = 90f;

        [SerializeField]
        private LayerMask groundLayers = ~0;

        [Header("Ground check")]
        [SerializeField]
        private float groundProbeDistance = 0.2f;

        [SerializeField]
        private float groundProbeRadius = 0.28f;

        private Rigidbody body;
        private CapsuleCollider capsule;

        private Vector2 moveInput;
        private bool jumpPressedThisFrame;
        private bool grounded;
        private float jumpBufferedUntil;
        private float lastGroundedAt;

        /// <summary>True while the capsule is standing on something in groundLayers.</summary>
        public bool IsGrounded => grounded;

        /// <summary>Horizontal speed in metres per second, for the animator.</summary>
        public float PlanarSpeed => new Vector2(body.velocity.x, body.velocity.z).magnitude;

        private void Awake()
        {
            body = GetComponent<Rigidbody>();
            capsule = GetComponent<CapsuleCollider>();
            body.interpolation = RigidbodyInterpolation.Interpolate;
        }

        private void Update()
        {
            moveInput = new Vector2(Input.GetAxisRaw("Horizontal"), Input.GetAxisRaw("Vertical"));

            if (Input.GetButtonDown("Jump") || Input.GetButtonDown("Submit"))
            {
                jumpPressedThisFrame = true;
            }
        }

        private void FixedUpdate()
        {
            grounded = GroundCheck();

            if (grounded)
            {
                lastGroundedAt = Time.time;
            }

            HandleMove();
            HandleJump();
        }

        /// <summary>
        /// Accelerates the body towards the input direction at the current speed cap.
        /// </summary>
        private void HandleMove()
        {
            float cap = Input.GetButton("Sprint") ? sprintSpeed : walkSpeed;
            Vector3 wish = transform.TransformDirection(new Vector3(moveInput.x, 0f, moveInput.y));

            if (wish.sqrMagnitude > 1f)
            {
                wish.Normalize();
            }

            Vector3 target = wish * cap;
            Vector3 planar = new Vector3(body.velocity.x, 0f, body.velocity.z);
            Vector3 delta = Vector3.MoveTowards(planar, target, acceleration * Time.fixedDeltaTime);

            body.velocity = new Vector3(delta.x, body.velocity.y, delta.z);
        }

        /// <summary>
        /// Sphere-casts down from the base of the capsule and reports whether anything in
        /// groundLayers is within groundProbeDistance.
        /// </summary>
        private bool GroundCheck()
        {
            Vector3 origin = transform.position + (Vector3.up * groundProbeRadius);

            return Physics.SphereCast(
                origin,
                groundProbeRadius,
                Vector3.down,
                out _,
                groundProbeDistance + groundProbeRadius,
                groundLayers,
                QueryTriggerInteraction.Ignore);
        }

        /// <summary>
        /// Applies the jump impulse when a press is pending, holding that press for
        /// jumpBufferMs so it survives the frames where the capsule is still landing.
        /// </summary>
        private void HandleJump()
        {
            if (jumpPressedThisFrame)
            {
                jumpBufferedUntil = Time.time + (jumpBufferMs / 1000f);
                jumpPressedThisFrame = false;
            }

            if (Time.time > jumpBufferedUntil)
            {
                return;
            }

            bool withinCoyoteTime = Time.time <= lastGroundedAt + (coyoteTimeMs / 1000f);

            if (!grounded && !withinCoyoteTime)
            {
                return;
            }

            jumpBufferedUntil = 0f;
            body.velocity = new Vector3(body.velocity.x, 0f, body.velocity.z);
            body.AddForce(Vector3.up * jumpImpulse, ForceMode.VelocityChange);
        }
    }
}
